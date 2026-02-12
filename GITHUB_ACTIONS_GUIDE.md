# 🚀 GitHub Actions - Guia de Uso

Este repositório possui workflows automatizados para compilar o APK do RunApp na nuvem.

## 📦 Workflows Disponíveis

### 1. Android Build (Build Automático)
**Arquivo:** `.github/workflows/android-build.yml`

**Quando executa:**
- Push na branch `main` ou `develop`
- Pull requests para a branch `main`
- Manualmente através do GitHub Actions

**O que gera:**
- ✅ APK Debug (para testes)
- ✅ APK Release não assinado

### 2. Release Build (Build de Release)
**Arquivo:** `.github/workflows/release-build.yml`

**Quando executa:**
- Criação de tags no formato `v*` (ex: `v1.0.0`, `v2.1.3`)
- Manualmente através do GitHub Actions

**O que gera:**
- ✅ APK Release assinado (se configurado)
- ✅ GitHub Release com o APK anexado

## 🎯 Como Usar

### Download do APK após Build

1. Acesse a aba **Actions** no seu repositório GitHub
2. Clique no workflow executado (ex: "Android Build")
3. Role até a seção **Artifacts**
4. Clique no artifact para baixar o APK

### Executar Build Manual

1. Vá em **Actions** → **Android Build** ou **Release Build**
2. Clique em **Run workflow**
3. Selecione a branch
4. Clique em **Run workflow**
5. Aguarde a compilação terminar
6. Baixe o APK nos artifacts

### Criar Release com Tag

```bash
# Criar e enviar uma tag
git tag v1.0.0
git push origin v1.0.0
```

Isso irá:
- Compilar o APK automaticamente
- Criar um GitHub Release
- Anexar o APK no release para download público

## 🔐 Assinatura do APK (Opcional)

Para gerar APKs assinados automaticamente, você precisa configurar secrets no GitHub:

### 1. Gerar/Preparar sua Keystore

```bash
# Se ainda não tem uma keystore, crie uma:
keytool -genkey -v -keystore runapp.jks -alias runapp -keyalg RSA -keysize 2048 -validity 10000

# Converter para Base64
base64 runapp.jks > keystore_base64.txt
```

### 2. Adicionar Secrets no GitHub

1. Vá em **Settings** → **Secrets and variables** → **Actions**
2. Clique em **New repository secret**
3. Adicione os seguintes secrets:

| Nome | Valor |
|------|-------|
| `KEYSTORE_BASE64` | Conteúdo do arquivo `keystore_base64.txt` |
| `KEYSTORE_PASSWORD` | Senha da keystore |
| `KEY_ALIAS` | Alias da chave (ex: `runapp`) |
| `KEY_PASSWORD` | Senha da chave |

### 3. Atualizar build.gradle.kts

Adicione no `app/build.gradle.kts`:

```kotlin
android {
    // ... código existente ...
    
    signingConfigs {
        create("release") {
            storeFile = file("../keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

## 📋 Estrutura dos Artifacts

Após o build, você terá:

```
Artifacts/
├── RunApp-debug-v1.0/
│   └── app-debug.apk
└── RunApp-release-v1.0/
    └── app-release-unsigned.apk (ou app-release.apk se assinado)
```

## 🔄 Versionamento

O workflow detecta automaticamente a versão do APK através do arquivo `app/build.gradle.kts`:

```kotlin
defaultConfig {
    versionCode = 1        // Incrementar a cada build
    versionName = "1.0"    // Versão visível (ex: 1.0, 2.0, 2.1)
}
```

## ⚡ Dicas

1. **Build mais rápido**: O workflow usa cache do Gradle para acelerar compilações subsequentes

2. **Múltiplos APKs**: Para gerar variantes diferentes (por exemplo, para diferentes arquiteturas), você pode configurar `splits` no `build.gradle.kts`

3. **Testes automáticos**: Adicione testes unitários e o workflow pode executá-los automaticamente antes de gerar o APK

4. **Notificações**: Configure notificações no GitHub para ser avisado quando o build terminar

## 🐛 Troubleshooting

### Build falha com erro de permissão
```bash
# Execute localmente:
chmod +x gradlew
git add gradlew
git commit -m "Fix gradlew permissions"
git push
```

### APK não é gerado
- Verifique os logs do workflow em Actions
- Certifique-se de que o `build.gradle.kts` está configurado corretamente
- Verifique se todas as dependências estão disponíveis

### Keystore não encontrada
- Verifique se o secret `KEYSTORE_BASE64` está configurado corretamente
- Certifique-se de que o Base64 foi gerado corretamente
- Verifique se as senhas nos secrets estão corretas

## 📚 Recursos Adicionais

- [Documentação GitHub Actions](https://docs.github.com/en/actions)
- [Android Gradle Plugin](https://developer.android.com/studio/build)
- [Assinar seu app](https://developer.android.com/studio/publish/app-signing)

---

**Dúvidas?** Abra uma issue no repositório! 🎯
