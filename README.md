# Dutrail — app Android

App Android do Dutrail para gravar corridas com GPS e enviá-las à plataforma. Tem login por email, lista e detalhe de atividades, gravação com relógio de corrida, geração de arquivos `.fit` com fila de envio offline e mapa do trajeto.

## Stack

- Kotlin, minSdk 26 (Android 8.0)
- Jetpack Compose com Material 3
- MVVM com camadas ui/domain/data e Hilt
- Coroutines e Flow
- Retrofit, OkHttp e kotlinx.serialization
- Room, DataStore (sessão cifrada com o Android Keystore), WorkManager e Paging 3
- Play Services Location, com foreground service
- Garmin FIT SDK
- MapLibre Native com tiles do OpenStreetMap

## Organização

```
com.dutrail.app
├── core/        rede (clientes, erros), Room, utilitários, tema e componentes
├── auth/        data (API, sessão cifrada) · domain (validação) · ui (login/cadastro)
├── activities/  data (API, paginação) · domain · ui (lista, detalhe)
├── recording/   domain (lógica pura) · data (Room, localização, .fit, upload) · service · ui
└── navigation/  rotas e raiz
```

## Configuração

Nenhuma URL fica no repositório. O build lê estas propriedades:

| Propriedade | Onde definir | Uso |
|---|---|---|
| `dutrail.api.url` | `local.properties` ou `-P` | URL base da API no build debug |
| `DUTRAIL_API_URL` | propriedade Gradle ou variável de ambiente | URL base da API no build release |
| `DUTRAIL_TILE_URL` | propriedade Gradle | modelo de URL dos tiles do mapa (opcional) |
| `DUTRAIL_TILE_ATTRIBUTION` | propriedade Gradle | texto de atribuição dos tiles (opcional) |

O app só aceita HTTPS. Se a URL da API faltar ou for inválida, a geração do APK falha com uma mensagem dizendo o que corrigir.

## Testes

```bash
./gradlew :app:testDebugUnitTest
```

Os testes cobrem a lógica de gravação (distância, filtro de pontos, pace, laps e elevação), a codificação do `.fit` (conferida com o decodificador oficial da Garmin), a renovação da sessão e a fila de envio.

## Tiles do mapa

Sem `DUTRAIL_TILE_URL`, o mapa usa o servidor de tiles público do OpenStreetMap. Pela [política de uso do OSM](https://operations.osmfoundation.org/policies/tiles/), ele só serve para desenvolvimento e uso leve: para distribuir o app, configure outro provedor. O app se identifica no User-Agent, mantém a atribuição visível e não baixa regiões para uso offline.

## Licenças de terceiros

- **Garmin FIT SDK** (`com.garmin:fit`): *FIT Protocol License Agreement*. É proprietária, gratuita e não exclusiva, com as restrições de uso definidas pela Garmin.
- **MapLibre Native**: BSD-2-Clause.
- **Dados do mapa**: © OpenStreetMap contributors, sob a ODbL. A atribuição aparece no mapa.
