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

## Deploy a Railway

El repo ya trae un `Dockerfile` multi-stage (probado localmente con `docker build` + un Postgres
de prueba) y toma la config de Postgres de las variables `PGHOST`/`PGPORT`/`PGDATABASE`/`PGUSER`/`PGPASSWORD`,
que es como Railway inyecta las credenciales de su plugin de Postgres automáticamente. También lee
`PORT` para el puerto HTTP, que Railway también inyecta solo.

1. Pushear este repo a GitHub (ver más abajo si todavía no lo hiciste).
2. En [railway.app](https://railway.app), crear un proyecto nuevo → "Deploy from GitHub repo" → elegir este repo.
   Railway detecta el `Dockerfile` y lo usa para buildear.
3. En el mismo proyecto, "+ New" → "Database" → "Add PostgreSQL". Railway conecta automáticamente
   `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` al servicio de la app (no hace falta
   setearlas a mano ni usar `DB_PASSWORD`, esa es solo para uso local con `docker-compose.yml`).
4. En el servicio de la app, pestaña "Variables", agregar:
   - `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET` (del Spotify Developer Dashboard)
   - `TOKEN_ENCRYPTION_KEY` (`openssl rand -base64 32`)
   - `LASTFM_API_KEY`
   - `COOKIE_SECURE=true` (obligatorio: sin esto el navegador no manda la cookie de sesión sobre HTTPS)
   - `SPOTIFY_REDIRECT_URI=https://<tu-dominio-de-railway>/auth/spotify/callback` (Railway te da el
     dominio `*.up.railway.app` apenas hace el primer deploy, en Settings → Networking → "Generate Domain")
5. Volver al Spotify Developer Dashboard → tu app → Settings → agregar exactamente esa misma URL
   a "Redirect URIs", y agregar como usuarios de la app (o pasarla a modo producción) a los 4 amigos
   que se van a loguear.
6. Redeploy. Listo, la URL de Railway es la que comparten entre los 5.

## Pushear a GitHub (si todavía no lo hiciste)

```bash
gh auth login          # o creá el repo a mano en github.com/new
gh repo create spotify-recommender --private --source=. --remote=origin
git push -u origin master
```

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
