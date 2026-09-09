# Spotify Recommender

App que analiza una playlist de Spotify y genera recomendaciones de canciones nuevas usando
similitud de Last.fm, evitando repetir temas que ya están en la playlist.

Pensada para que **varias personas usen la misma instancia**, cada una conectando su propia
cuenta de Spotify (OAuth2 Authorization Code). Cada usuario ve solo sus propias playlists y
recomendaciones.

## Cómo funciona

1. Cada persona entra a la app y toca "Conectar con Spotify" → login OAuth.
2. Elige una de sus playlists.
3. Un job en segundo plano sincroniza los tracks de esa playlist (`PlaylistSyncService`, cada 30s)
   y otro genera recomendaciones vía Last.fm + búsqueda en Spotify (`RecommendationService`, cada 60s).
4. La UI muestra las recomendaciones nuevas, con cooldown para no repetir la misma sugerencia
   ni resincronizar de más.

## Requisitos

- JDK 21
- Docker (para Postgres) o una instancia de Postgres propia
- Una app registrada en el [Spotify Developer Dashboard](https://developer.spotify.com/dashboard)
- Una API key de [Last.fm](https://www.last.fm/api/account/create)

## Configuración

La app no tiene ningún secreto hardcodeado: todo se toma de variables de entorno
(ver `src/main/resources/application.properties`).

| Variable | Descripción |
|---|---|
| `DB_PASSWORD` | Password de Postgres |
| `SPOTIFY_CLIENT_ID` | Client ID de tu app de Spotify |
| `SPOTIFY_CLIENT_SECRET` | Client Secret de tu app de Spotify |
| `SPOTIFY_REDIRECT_URI` | Debe coincidir EXACTO con el registrado en el dashboard de Spotify (default: `http://127.0.0.1:8080/auth/spotify/callback`) |
| `TOKEN_ENCRYPTION_KEY` | Clave AES-256 en base64 para cifrar los tokens de Spotify en la base. Generarla con `openssl rand -base64 32` |
| `LASTFM_API_KEY` | API key de Last.fm |

En el dashboard de Spotify agregá tu `SPOTIFY_REDIRECT_URI` a la lista de Redirect URIs de la app,
y agregá como usuarios de prueba (o pasá la app a modo producción) a cada amigo que se vaya a loguear.

## Levantar el proyecto

```bash
docker compose up -d          # levanta Postgres

export DB_PASSWORD=...
export SPOTIFY_CLIENT_ID=...
export SPOTIFY_CLIENT_SECRET=...
export TOKEN_ENCRYPTION_KEY=...
export LASTFM_API_KEY=...

./mvnw spring-boot:run
```

La app queda en `http://127.0.0.1:8080`.

## Usarla con más de una persona

La app está pensada para correr **una sola instancia compartida** (por ejemplo, en un VPS)
a la que cada amigo entra y conecta su propia cuenta de Spotify. La autenticación entre
requests usa una cookie de sesión `HttpOnly` (no hay contraseñas propias de la app).

Si van a exponerla fuera de `localhost`, corranla detrás de HTTPS (proxy reverso tipo Caddy/nginx)
y actualicen `SPOTIFY_REDIRECT_URI` al dominio real.

## Deuda técnica conocida / posibles mejoras

- Las sesiones (`SessionStore`) y el estado de OAuth (`OAuthStateStore`) viven en memoria: un
  reinicio del server desloguea a todos. Para 5 usuarios no es un problema real, pero si se
  necesita persistencia entre reinicios habría que moverlas a la base o a Redis.
- `PlaylistSyncService.syncPlaylists()` recorre usuarios secuencialmente cada 30s; si el número
  de usuarios crece mucho, un ciclo podría tardar más de 30s y correrse (no es un bug, es un
  límite de diseño para grupos chicos).
- No hay tests más allá del `contextLoads` por defecto. `@SpringBootTest` necesita Postgres
  corriendo y las variables de entorno seteadas para poder ejecutarse.
