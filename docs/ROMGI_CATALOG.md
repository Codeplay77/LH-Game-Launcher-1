# Catálogo Romgi no LightHouse

Esta versão adiciona um cliente nativo Kotlin compatível com o formato documentado do Romgi (`version.json` + `romdb.db.gz`, schema SQLite v4).

## O que foi adicionado

- Catálogo local SQLite em `externalFiles/romgi-catalog/`.
- Atualização por URL base HTTPS.
- Leitura de plataformas, títulos, regiões, capas e links.
- Busca por texto e plataforma.
- Download sob demanda para a pasta SAF configurada no perfil do console.
- Progresso de download.
- Exclusão automática do arquivo parcial em caso de falha.
- Entrada **Configurações > Library > Catálogo retrô**.
- Após instalar, o provider `folder` existente do LH encontra a ROM na próxima varredura.

## Como usar

1. Compile e instale o APK.
2. Configure cada console no LH e conceda acesso à pasta de ROMs.
3. Abra **Settings > Library > Catálogo retrô**.
4. Informe uma URL HTTPS que publique `version.json` e `romdb.db.gz`.
5. Atualize o catálogo, pesquise um jogo e escolha **Instalar**.

A implementação não inclui `romdb.db.gz`, ROMs, arquivos RAP/zRIF, capas nem links de terceiros. O repositório deve ser apontado somente para um catálogo que você esteja autorizado a usar/distribuir.

## Formato esperado

```text
https://exemplo/catalog/version.json
https://exemplo/catalog/romdb.db.gz
```

O banco precisa conter as tabelas documentadas pelo Romgi, incluindo `platforms`, `entries`, `regions`, `regions_entries` e `links`.

## Build

Requisitos do projeto original:

- JDK 17 ou superior com `javac`;
- Android SDK API 35;
- `local.properties` com `sdk.dir=/caminho/para/Android/Sdk`.

```bash
./gradlew :app:assembleDebug
```

O sandbox utilizado para esta entrega tinha Java runtime, mas não tinha Android SDK; por isso a compilação final do APK precisa ser executada em uma máquina Android configurada.

## Arquivos principais

- `app/src/main/java/org/lighthouse/data/RomgiCatalog.kt`
- `app/src/main/java/org/lighthouse/ui/RomgiCatalogScreen.kt`
- `app/src/main/java/org/lighthouse/ui/MenuTree.kt`
- `app/src/main/java/org/lighthouse/ui/MainActivity.kt`

## Licenças e conteúdo

O LH original permanece sob Apache-2.0. O cliente novo é código de integração separado sob Apache-2.0. O catálogo e os arquivos baixados continuam sujeitos às licenças e aos termos de cada fonte.
