// CorridaViewModel.kt - MODIFICAÇÕES NECESSÁRIAS

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// MODIFICAÇÃO 1: Substitua a função iniciarGPS() existente (linha ~213)
// pela versão melhorada abaixo:
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

private fun iniciarGPS() {
    viewModelScope.launch {
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            
            // Tentar obter última localização conhecida para início mais rápido
            // Isso permite que o app já mostre uma posição aproximada enquanto
            // aguarda o primeiro fix GPS preciso
            fusedClient.lastLocation.addOnSuccessListener { location ->
                location?.let { 
                    android.util.Log.d("CorridaVM", """
                        📍 Última localização conhecida obtida:
                           Lat: ${it.latitude}
                           Lng: ${it.longitude}
                           Accuracy: ${it.accuracy}m
                           Tempo: ${java.util.Date(it.time)}
                    """.trimIndent())
                }
            }.addOnFailureListener { e ->
                android.util.Log.w("CorridaVM", "⚠️ Não foi possível obter última localização: ${e.message}")
            }
            
            android.util.Log.d("CorridaVM", "✅ GPS client inicializado com sucesso")
            
        } catch (e: SecurityException) {
            android.util.Log.e("CorridaVM", "❌ Erro ao iniciar GPS: ${e.message}")
            _uiState.value = _uiState.value.copy(
                erro = "Permissões de GPS não concedidas. " +
                       "Vá em Configurações > Apps > RunApp > Permissões e ative 'Localização'"
            )
        } catch (e: Exception) {
            android.util.Log.e("CorridaVM", "❌ Erro inesperado ao iniciar GPS", e)
            _uiState.value = _uiState.value.copy(
                erro = "Erro ao inicializar GPS: ${e.message}"
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// MODIFICAÇÃO 2: Substitua a função salvarCorrida() existente (linha ~417)
// pela versão com validações melhoradas abaixo:
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

fun salvarCorrida() {
    val state = _uiState.value
    
    // ✅ VALIDAÇÃO 1: Verificar se há pontos GPS
    if (state.rota.isEmpty()) {
        _uiState.value = state.copy(
            salvamentoEstado = SalvamentoEstado.ERRO,
            erroSalvamento = """
                Nenhum ponto GPS foi coletado durante a corrida.
                
                Possíveis causas:
                • GPS do celular desligado
                • Permissões de localização não concedidas
                • Sinal GPS muito fraco (ambiente interno)
                
                Solução: Verifique as configurações e tente novamente em área aberta.
            """.trimIndent()
        )
        return
    }
    
    // ✅ VALIDAÇÃO 2: Verificar distância mínima
    if (state.distanciaMetros < 50) {
        _uiState.value = state.copy(
            salvamentoEstado = SalvamentoEstado.ERRO,
            erroSalvamento = """
                Distância muito curta: ${state.distanciaMetros.toInt()} metros.
                
                Percorra pelo menos 50 metros antes de salvar a corrida.
                (Foram coletados ${state.rota.size} pontos GPS)
            """.trimIndent()
        )
        return
    }
    
    // ✅ VALIDAÇÃO 3: Verificar tempo mínimo
    if (state.tempoTotalSegundos < 30) {
        _uiState.value = state.copy(
            salvamentoEstado = SalvamentoEstado.ERRO,
            erroSalvamento = """
                Tempo muito curto: ${state.tempoTotalSegundos} segundos.
                
                Corra por pelo menos 30 segundos antes de salvar.
            """.trimIndent()
        )
        return
    }
    
    // Prevenir múltiplos salvamentos simultâneos
    if (state.salvamentoEstado == SalvamentoEstado.SALVANDO) {
        android.util.Log.w("CorridaVM", "⚠️ Salvamento já em andamento, ignorando nova tentativa")
        return
    }

    // Log de debug antes de salvar
    android.util.Log.d("CorridaVM", """
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        💾 INICIANDO SALVAMENTO
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        Distância: ${"%.2f".format(state.distanciaMetros / 1000)} km
        Tempo: ${state.tempoFormatado}
        Pace médio: ${state.paceMedia}
        Pontos GPS: ${state.rota.size}
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    """.trimIndent())

    _uiState.value = state.copy(
        salvamentoEstado = SalvamentoEstado.SALVANDO,
        erroSalvamento = null
    )

    viewModelScope.launch {
        try {
            val apiKey    = container.preferencesRepository.apiKey.first()
            val athleteId = container.preferencesRepository.athleteId.first()

            if (athleteId == null) {
                _uiState.value = _uiState.value.copy(
                    salvamentoEstado = SalvamentoEstado.ERRO,
                    erroSalvamento = "ID do atleta não configurado. Configure em Ajustes."
                )
                return@launch
            }

            val repo = workoutRepo
                ?: container.createWorkoutRepository(apiKey ?: "").also { workoutRepo = it }

            val nomeAtividade = "Corrida RunApp - ${
                java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")
                )
            }"

            repo.salvarAtividade(
                context    = context,
                athleteId  = athleteId,
                nomeAtividade = nomeAtividade,
                distanciaMetros = state.distanciaMetros,
                tempoSegundos   = state.tempoTotalSegundos,
                paceMedia       = state.paceMedia,
                rota            = state.rota
            ).fold(
                onSuccess = { arquivo ->
                    // Guarda referência do arquivo para o upload posterior
                    arquivoGpxSalvo = arquivo
                    _uiState.value = _uiState.value.copy(
                        salvamentoEstado = SalvamentoEstado.SALVO
                    )
                    android.util.Log.d("CorridaVM", "✅ GPX salvo com sucesso: ${arquivo.absolutePath}")
                },
                onFailure = { e ->
                    android.util.Log.e("CorridaVM", "❌ Erro ao salvar GPX", e)
                    _uiState.value = _uiState.value.copy(
                        salvamentoEstado = SalvamentoEstado.ERRO,
                        erroSalvamento = "Erro ao salvar: ${e.message}"
                    )
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("CorridaVM", "❌ Erro inesperado no salvamento", e)
            _uiState.value = _uiState.value.copy(
                salvamentoEstado = SalvamentoEstado.ERRO,
                erroSalvamento = "Erro inesperado: ${e.message}"
            )
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// MODIFICAÇÃO 3 (OPCIONAL): Adicionar logs detalhados no onNovaLocalizacao
// Encontre a função onNovaLocalizacao (linha ~230) e adicione este log
// logo no início, antes do return do accuracy check:
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

fun onNovaLocalizacao(location: Location) {
    val state = _uiState.value
    if (state.fase != FaseCorrida.CORRENDO) return

    // Log detalhado a cada 10 pontos (para não poluir demais o logcat)
    if (state.rota.size % 10 == 0) {
        android.util.Log.d("GPS_DEBUG", """
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            📍 Ponto GPS #${state.rota.size}
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            Lat: ${location.latitude}
            Lng: ${location.longitude}
            Accuracy: ${location.accuracy}m
            Speed: ${if (location.hasSpeed()) "${location.speed} m/s" else "N/A"}
            Time: ${java.util.Date(location.time)}
            Distância total: ${"%.2f".format(state.distanciaMetros / 1000)} km
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        """.trimIndent())
    }

    // ... resto da função continua normalmente
}
