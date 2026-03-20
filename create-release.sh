#!/bin/bash

# Script para criar uma nova release do RunApp
# Uso: ./create-release.sh 1.0.0 "Descrição da versão"

set -e

VERSION=$1
DESCRIPTION=$2

if [ -z "$VERSION" ]; then
    echo "❌ Erro: Versão não informada"
    echo "Uso: ./create-release.sh <versão> [descrição]"
    echo "Exemplo: ./create-release.sh 1.0.0 'Primeira versão'"
    exit 1
fi

# Validar formato da versão
if ! [[ $VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "❌ Erro: Formato de versão inválido"
    echo "Use o formato: X.Y.Z (ex: 1.0.0)"
    exit 1
fi

TAG="v$VERSION"

echo "📦 Criando release $TAG..."

# Verificar se está em um repositório git
if ! git rev-parse --git-dir > /dev/null 2>&1; then
    echo "❌ Erro: Não está em um repositório Git"
    exit 1
fi

# Verificar se há mudanças não commitadas
if ! git diff-index --quiet HEAD --; then
    echo "⚠️  Atenção: Há mudanças não commitadas"
    read -p "Deseja continuar mesmo assim? (s/n) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Ss]$ ]]; then
        echo "Operação cancelada"
        exit 1
    fi
fi

# Verificar se a tag já existe
if git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "❌ Erro: Tag $TAG já existe"
    echo "Use uma versão diferente ou delete a tag existente com:"
    echo "  git tag -d $TAG"
    echo "  git push origin :refs/tags/$TAG"
    exit 1
fi

# Atualizar versionName no build.gradle.kts
echo "📝 Atualizando versionName para $VERSION..."
sed -i "s/versionName = \".*\"/versionName = \"$VERSION\"/" app/build.gradle.kts

# Perguntar se deseja incrementar versionCode
CURRENT_VERSION_CODE=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts)
NEW_VERSION_CODE=$((CURRENT_VERSION_CODE + 1))

echo "Version Code atual: $CURRENT_VERSION_CODE"
read -p "Incrementar para $NEW_VERSION_CODE? (s/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Ss]$ ]]; then
    sed -i "s/versionCode = $CURRENT_VERSION_CODE/versionCode = $NEW_VERSION_CODE/" app/build.gradle.kts
    echo "✅ Version Code atualizado para $NEW_VERSION_CODE"
fi

# Commit das mudanças de versão
echo "💾 Commitando mudanças de versão..."
git add app/build.gradle.kts
git commit -m "chore: bump version to $VERSION" || true

# Criar e enviar a tag
echo "🏷️  Criando tag $TAG..."
if [ -z "$DESCRIPTION" ]; then
    git tag -a "$TAG" -m "Release $VERSION"
else
    git tag -a "$TAG" -m "Release $VERSION: $DESCRIPTION"
fi

echo "📤 Enviando para o GitHub..."
git push origin main  # ou develop, dependendo da sua branch padrão
git push origin "$TAG"

echo ""
echo "✅ Release criada com sucesso!"
echo ""
echo "🎉 O GitHub Actions vai:"
echo "   1. Compilar o APK automaticamente"
echo "   2. Criar um GitHub Release"
echo "   3. Anexar o APK no release"
echo ""
echo "📱 Acompanhe o progresso em:"
echo "   https://github.com/$(git config --get remote.origin.url | sed 's/.*github.com[:/]\(.*\)\.git/\1/')/actions"
echo ""
echo "⏱️  O build deve levar cerca de 3-5 minutos para completar."
