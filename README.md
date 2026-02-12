# 🏃 RunApp - Aplicativo de Corrida

Aplicativo Android desenvolvido com Jetpack Compose para acompanhamento de treinos de corrida.

## 🚀 Build Automatizado com GitHub Actions

Este projeto possui **compilação automática na nuvem** usando GitHub Actions! Você não precisa ter o Android Studio instalado para gerar o APK.

### ⚡ Como Funciona

Toda vez que você faz push para a branch principal, o GitHub Actions:
1. ✅ Compila o projeto automaticamente
2. ✅ Gera os APKs (Debug e Release)
3. ✅ Disponibiliza os APKs para download
4. ✅ Cria releases automáticas (quando você cria uma tag)

### 📦 Download do APK

#### Opção 1: Via Actions (após cada commit)
1. Acesse: **Actions** → Último workflow executado
2. Role até **Artifacts**
3. Baixe o APK desejado:
   - `RunApp-debug-vX.X` - Para testes
   - `RunApp-release-vX.X` - Para distribuição

#### Opção 2: Via Releases (versões estáveis)
1. Acesse a aba **Releases**
2. Baixe o APK da versão desejada
3. Instale no seu dispositivo Android

### 🏷️ Criar Nova Release

#### Método 1: Via Script (Recomendado)
```bash
# Tornar o script executável (primeira vez)
chmod +x create-release.sh

# Criar release
./create-release.sh 1.0.0 "Primeira versão pública"
```

#### Método 2: Via Git Manual
```bash
# Atualizar versão no app/build.gradle.kts
# versionName = "1.0.0"
# versionCode = 1

# Criar tag
git tag -a v1.0.0 -m "Release 1.0.0"
git push origin v1.0.0
```

#### Método 3: Via Interface Web
1. Vá em **Actions** → **Release Build**
2. Clique em **Run workflow**
3. Preencha a versão (ex: 1.0.0)
4. Clique em **Run workflow**

### 🔐 Assinatura do APK (Opcional mas Recomendado)

Para publicar na Google Play Store, você precisa assinar o APK:

1. **Gerar keystore** (apenas uma vez):
```bash
keytool -genkey -v -keystore runapp.jks -alias runapp -keyalg RSA -keysize 2048 -validity 10000
```

2. **Configurar secrets no GitHub**:
   - Vá em `Settings` → `Secrets and variables` → `Actions`
   - Adicione os secrets:
     - `KEYSTORE_BASE64`: `base64 runapp.jks > keystore.txt` (conteúdo do arquivo)
     - `KEYSTORE_PASSWORD`: senha da keystore
     - `KEY_ALIAS`: `runapp` (ou seu alias)
     - `KEY_PASSWORD`: senha da chave

3. **Habilitar assinatura**:
   - Copie o conteúdo de `app/build.gradle.kts.signing-example`
   - Cole em `app/build.gradle.kts`
   - Faça commit e push

Pronto! Os próximos builds serão **assinados automaticamente**! 🎉

## 🛠️ Desenvolvimento Local

### Pré-requisitos
- Android Studio Hedgehog ou superior
- JDK 17
- Android SDK 35

### Configuração

1. Clone o repositório:
```bash
git clone https://github.com/seu-usuario/RunApp.git
cd RunApp
```

2. Crie o arquivo `local.properties`:
```bash
cp local.properties.template local.properties
# Edite com o caminho do seu Android SDK
```

3. Abra no Android Studio e sincronize o Gradle

4. Execute o app no emulador ou dispositivo

### Build Local

```bash
# Debug APK
./gradlew assembleDebug

# Release APK
./gradlew assembleRelease

# APKs estarão em: app/build/outputs/apk/
```

## 📱 Funcionalidades

- ✅ Rastreamento de corrida com GPS
- ✅ Estatísticas em tempo real (distância, ritmo, tempo)
- ✅ Coach de áudio
- ✅ Histórico de treinos
- ✅ Integração com Intervals.icu
- ✅ Mapas interativos
- ✅ Material Design 3

## 🏗️ Tecnologias

- **Linguagem**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Arquitetura**: MVVM
- **Async**: Kotlin Coroutines + Flow
- **Networking**: Retrofit + OkHttp
- **Localização**: Google Play Services Location
- **Mapas**: Google Maps Compose
- **Storage**: DataStore Preferences

## 📚 Estrutura do Projeto

```
RunApp/
├── .github/
│   └── workflows/          # GitHub Actions workflows
│       ├── android-build.yml    # Build automático
│       └── release-build.yml    # Releases
├── app/
│   ├── src/main/kotlin/com/runapp/
│   │   ├── data/          # Camada de dados
│   │   ├── service/       # Serviços (GPS, Audio)
│   │   └── ui/            # Interface do usuário
│   └── build.gradle.kts   # Dependências do app
├── gradle/                # Gradle wrapper
├── create-release.sh      # Script de release
└── GITHUB_ACTIONS_GUIDE.md # Guia completo de CI/CD
```

## 🤝 Contribuindo

1. Fork o projeto
2. Crie uma branch: `git checkout -b feature/nova-feature`
3. Commit suas mudanças: `git commit -m 'feat: adiciona nova feature'`
4. Push para a branch: `git push origin feature/nova-feature`
5. Abra um Pull Request

O GitHub Actions vai automaticamente compilar e testar seu PR!

## 📄 Licença

Este projeto está sob a licença MIT. Veja o arquivo [LICENSE](LICENSE) para mais detalhes.

## 📞 Suporte

- 📖 [Guia Completo de GitHub Actions](GITHUB_ACTIONS_GUIDE.md)
- 🐛 [Reportar Bug](../../issues)
- 💡 [Sugerir Feature](../../issues)

---

**Desenvolvido com ❤️ e Jetpack Compose**
