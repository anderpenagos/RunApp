# 🏃 RunApp

Aplicativo Android de corrida desenvolvido com Jetpack Compose. Integra-se ao [Intervals.icu](https://intervals.icu) para importar planos de treino e usa o **Gemini 2.5 Flash** para gerar análises inteligentes de cada atividade.

---

## 📱 Funcionalidades

### Treinos Guiados (Intervals.icu)
- Importa automaticamente os treinos da semana do Intervals.icu
- Exibe cada passo do treino (aquecimento, tiros, recuperação, etc.) com pace-alvo
- Navegação manual entre passos (pular / voltar)
- Aviso de áudio quando o pace está fora da zona alvo

### Corrida Livre
- Inicia sem nenhum plano vinculado — só rastreamento GPS
- Mesmas métricas e salvamento que o modo guiado

### Rastreamento GPS em Tempo Real
- Posição filtrada com filtro de Kalman para reduzir ruído
- Distância, tempo, pace atual e pace médio atualizados a cada segundo
- Serviço em foreground com notificação persistente — continua rodando com a tela desligada
- **Auto-pause** configurável: pausa automaticamente ao detectar que o usuário parou
- Checkpoint periódico a cada 30 s — recupera a corrida mesmo se o app for morto pelo sistema

### Grade Adjusted Pace (GAP)
- Calcula o esforço equivalente em terreno plano usando o modelo de Minetti (2002)
- Anúncios de áudio específicos para subidas e descidas técnicas (grade < −15%)
- Splits por km exibem tanto o pace real quanto o GAP

### Coach de Áudio (Text-to-Speech)
- Anúncia o início da corrida e cada passo do treino
- Alerta de pace a cada km: "Km 3 — pace 5:12, GAP 4:58, subida de 4%"
- Contagem regressiva nos últimos 30 s de cada passo
- Debounce interno para evitar repetições em subidas longas

### Análise do Coach (IA — Gemini 2.5 Flash)
- Gerada automaticamente ao abrir o detalhe de uma atividade
- Analisa: adesão ao plano, esforço real (GAP), biomecânica (passada vs. baseline) e distribuição de zonas de pace
- Retorna 4 parágrafos objetivos com recomendação para o próximo treino
- **Cacheada no JSON local** — gerada uma única vez, sem custo de API nas próximas aberturas

### Métricas de Biomecânica
- Cadência (passos/min) via acelerômetro, suavizada com filtro de Kalman
- Comprimento de passada estimado e comparado com baseline histórico
- Queda > 5% sinaliza fadiga mecânica na análise do Coach

### Histórico e Detalhe de Atividade
- Lista todas as corridas salvas localmente
- Gráficos interativos de pace + elevação, GAP e cadência por km
- Distribuição de tempo por zona de pace (Z1–Z5)
- Splits por km com GAP e gradiente médio
- Mapa da rota com ponto selecionável no gráfico

### Salvamento e Upload
- Cada corrida gera dois arquivos em `getExternalFilesDir/gpx/`:
  - `corrida_YYYYMMDD_HHmmss.gpx` — track GPS completo
  - `corrida_YYYYMMDD_HHmmss.json` — metadados (distância, pace, splits, feedback do Coach…)
- Upload do GPX para o Intervals.icu diretamente pelo app (tela de resumo)

---

## 🔑 Chaves de API necessárias

O app depende de **três chaves**. Duas são compiladas no APK via `BuildConfig`; a terceira é configurada em tempo de execução pelo usuário.

### 1. Google Maps — `MAPS_API_KEY`

Usada para renderizar o mapa de rota dentro do app.

**Como obter:**
1. Acesse [console.cloud.google.com](https://console.cloud.google.com)
2. Crie ou selecione um projeto
3. Ative as APIs: **Maps SDK for Android** e **Places API**
4. Vá em **APIs & Services → Credentials → Create credentials → API key**
5. Recomendado: restrinja a chave ao package `com.runapp` (Android apps)

**Como configurar:**

| Ambiente | Comando |
|---|---|
| Linux / macOS | `export MAPS_API_KEY="AIza..."` |
| PowerShell | `$env:MAPS_API_KEY="AIza..."` |
| GitHub Actions | Secret `MAPS_API_KEY` em *Settings → Secrets → Actions* |

---

### 2. Google Gemini — `GEMINI_API_KEY`

Usada pelo Coach IA para gerar a análise pós-treino.

**Como obter:**
1. Acesse [aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey)
2. Clique em **Create API key**
3. Copie a chave gerada (`AIza...`)

> O modelo usado é `gemini-2.5-flash`. O timeout de leitura está configurado em 90 s para acomodar respostas mais longas.

**Como configurar:**

| Ambiente | Comando |
|---|---|
| Linux / macOS | `export GEMINI_API_KEY="AIza..."` |
| PowerShell | `$env:GEMINI_API_KEY="AIza..."` |
| GitHub Actions | Secret `GEMINI_API_KEY` em *Settings → Secrets → Actions* |

> ⚠️ **Atenção:** as duas chaves acima são lidas por `System.getenv()` **no momento do build** e compiladas no APK. Adicioná-las apenas ao GitHub Secrets não é suficiente — elas precisam estar expostas no bloco `env:` do step que executa o Gradle (já configurado nos workflows deste repositório).

---

### 3. Intervals.icu — configurada no app

O Athlete ID e a API Key do Intervals.icu **não entram no build**. O usuário os insere diretamente na tela de Configurações do app.

**Como obter:**
1. Acesse [intervals.icu](https://intervals.icu) → clique no ícone do seu perfil → **Settings**
2. Role até **Developer Settings**
3. Copie o **Athlete ID** (ex.: `i12345`) e a **API Key**

**Onde inserir no app:**
- Abra o RunApp → toque no ícone ⚙️ no canto superior direito da Home
- Preencha **Athlete ID** e **API Key**
- Toque em **Salvar**

---

## 🚀 Build via GitHub Actions

Todo push para `main` ou `develop` dispara o workflow **android-build** que compila o APK e o envia pelo Telegram.

### Secrets obrigatórios no repositório

| Secret | Descrição |
|---|---|
| `MAPS_API_KEY` | Chave do Google Maps |
| `GEMINI_API_KEY` | Chave do Google Gemini |
| `TELEGRAM_BOT_TOKEN` | Token do bot que envia o APK |
| `TELEGRAM_CHAT_ID` | ID do chat/canal de destino |

### Secrets opcionais (apenas para release assinado)

| Secret | Descrição |
|---|---|
| `KEYSTORE_BASE64` | Keystore em Base64 (`base64 runapp.jks`) |
| `KEYSTORE_PASSWORD` | Senha da keystore |
| `KEY_ALIAS` | Alias da chave |
| `KEY_PASSWORD` | Senha da chave |

### Workflows disponíveis

| Arquivo | Gatilho | O que faz |
|---|---|---|
| `android-build.yml` | Push em `main`/`develop` ou manual | Build debug + envia APK para o Telegram |
| `pr-check.yml` | Pull Request | Lint + build de verificação |
| `release-build.yml` | Manual ou tag | Build release assinado |

---

## 🛠️ Desenvolvimento local

### Pré-requisitos
- Android Studio Hedgehog ou superior
- JDK 17
- Android SDK 35

### Configuração

```bash
# 1. Clone o repositório
git clone https://github.com/seu-usuario/RunApp.git
cd RunApp

# 2. Configure o SDK
cp local.properties.template local.properties
# Edite local.properties com o caminho do seu Android SDK

# 3. Exporte as variáveis de ambiente (Linux/macOS)
export MAPS_API_KEY="AIza..."
export GEMINI_API_KEY="AIza..."

# 4. Build
./gradlew assembleDebug
# APK gerado em: app/build/outputs/apk/debug/
```

No **Windows (PowerShell)**:
```powershell
$env:MAPS_API_KEY="AIza..."
$env:GEMINI_API_KEY="AIza..."
.\gradlew assembleDebug
```

---

## 🏗️ Tecnologias

| Categoria | Tecnologia |
|---|---|
| Linguagem | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Arquitetura | MVVM + Repository |
| Async | Coroutines + Flow |
| Networking | Retrofit + OkHttp |
| Localização | Google Play Services Location |
| Mapas | Google Maps Compose |
| Storage | DataStore Preferences + Room + GPX/JSON local |
| Sensores | SensorManager (acelerômetro para cadência) |
| IA | Google Gemini 2.5 Flash |
| CI/CD | GitHub Actions + Telegram |

---

## 📂 Estrutura do projeto

```
RunApp/
├── .github/
│   └── workflows/
│       ├── android-build.yml     # Build debug + Telegram
│       ├── pr-check.yml          # Lint + build em PRs
│       └── release-build.yml     # Build release assinado
├── app/
│   └── src/main/kotlin/com/runapp/
│       ├── data/
│       │   ├── api/              # IntervalsApi (Retrofit)
│       │   ├── datastore/        # PreferencesRepository (credenciais Intervals)
│       │   ├── db/               # Room (RoutePointDao — backup de rota)
│       │   ├── model/            # Data classes
│       │   └── repository/
│       │       ├── CoachRepository.kt      # Gemini 2.5 Flash
│       │       ├── HistoricoRepository.kt  # Leitura/escrita GPX + JSON
│       │       └── WorkoutRepository.kt    # Intervals.icu + cálculo de splits/GAP
│       ├── service/
│       │   ├── RunningService.kt  # Foreground service: GPS, cadência, GAP, checkpoint
│       │   └── AudioCoach.kt      # TTS: anúncios de pace, passos, km
│       ├── ui/
│       │   ├── navigation/        # AppNavigation.kt
│       │   ├── screens/           # Home, Corrida, Resumo, Histórico, Detalhe, Config, Treinos
│       │   └── viewmodel/         # CorridaViewModel, HistoricoViewModel, etc.
│       └── util/
│           ├── DouglasPeucker.kt  # Simplificação de rota para display no mapa
│           └── PermissionHelper.kt
└── local.properties.template
```

---

## 📄 Licença

Este projeto está sob a licença MIT. Veja o arquivo [LICENSE](LICENSE) para mais detalhes.
