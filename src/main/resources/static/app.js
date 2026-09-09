const disconnectedView = document.getElementById('disconnectedView');
const connectedView = document.getElementById('connectedView');
const shellBody = document.getElementById('shellBody');
const accountInfoEl = document.getElementById('accountInfo');
const connectBtn = document.getElementById('connectBtn');
const choosePlaylistBtn = document.getElementById('choosePlaylistBtn');
const loadBtn = document.getElementById('loadBtn');
const disconnectBtn = document.getElementById('disconnectBtn');
const messageEl = document.getElementById('message');
const playlistsEl = document.getElementById('playlists');
const recommendationsEl = document.getElementById('recommendations');
const refreshBtn = document.getElementById('refreshBtn');
const toastEl = document.getElementById('toast');

let toastTimeoutId = null;

init();

async function init() {
    consumeLoginRedirect();
    await renderAccountState();
}

function consumeLoginRedirect() {
    const params = new URLSearchParams(window.location.search);
    if (params.get('connected') !== 'true') return;

    const displayName = params.get('displayName');
    window.history.replaceState({}, '', window.location.pathname);
    showToast(displayName ? `Conectado correctamente como ${displayName}` : 'Conectado correctamente');
}

async function fetchCurrentUser() {
    try {
        const response = await fetch('/auth/spotify/me');
        if (!response.ok) return null;
        return await response.json();
    } catch (err) {
        return null;
    }
}

async function renderAccountState() {
    const me = await fetchCurrentUser();
    if (!me) {
        disconnectedView.hidden = false;
        connectedView.hidden = true;
        shellBody.hidden = true;
        return;
    }
    disconnectedView.hidden = true;
    connectedView.hidden = false;
    shellBody.hidden = false;
    accountInfoEl.textContent = me.displayName ? `Conectado como ${me.displayName}` : 'Conectado';
}

async function handleSessionExpired() {
    showMessage('Tu sesión expiró. Volvé a conectar tu cuenta.');
    await renderAccountState();
}

connectBtn.addEventListener('click', () => {
    window.location.href = '/auth/spotify/login';
});

disconnectBtn.addEventListener('click', async () => {
    await fetch('/auth/spotify/logout', { method: 'POST' });
    playlistsEl.innerHTML = '';
    recommendationsEl.innerHTML = '';
    refreshBtn.hidden = true;
    showMessage('');
    await renderAccountState();
});

choosePlaylistBtn.addEventListener('click', loadPlaylists);
loadBtn.addEventListener('click', loadRecommendations);
refreshBtn.addEventListener('click', loadRecommendations);

recommendationsEl.addEventListener('click', (event) => {
    const playBtn = event.target.closest('.play-btn');
    if (!playBtn) return;
    event.preventDefault();
    openInSpotifyApp(playBtn.dataset.spotifyUri, playBtn.dataset.trackUrl);
});

function openInSpotifyApp(appUri, webUrl) {
    const fallbackTimer = setTimeout(() => {
        cleanup();
        window.open(webUrl, '_blank', 'noopener,noreferrer');
    }, 1500);

    function cleanup() {
        clearTimeout(fallbackTimer);
        document.removeEventListener('visibilitychange', onHide);
        window.removeEventListener('blur', onHide);
    }

    function onHide() {
        cleanup();
    }

    document.addEventListener('visibilitychange', onHide);
    window.addEventListener('blur', onHide);
    window.location.href = appUri;
}

async function loadPlaylists() {
    recommendationsEl.innerHTML = '';
    playlistsEl.innerHTML = '';
    refreshBtn.hidden = true;
    showMessage('Cargando tus playlists...');

    try {
        const response = await fetch('/playlists');
        if (response.status === 401) {
            await handleSessionExpired();
            return;
        }
        if (!response.ok) {
            showMessage(`No se pudieron cargar tus playlists (${response.status}).`);
            return;
        }
        const playlists = await response.json();
        if (playlists.length === 0) {
            showMessage('No se encontraron playlists en tu cuenta.');
            return;
        }
        showMessage('Elegí una playlist:');
        playlistsEl.innerHTML = playlists.map(renderPlaylistCard).join('');
        playlistsEl.querySelectorAll('[data-playlist-id]').forEach(card => {
            card.addEventListener('click', () => selectPlaylist(card.dataset.playlistId, card.dataset.playlistName));
        });
    } catch (err) {
        showMessage('No se pudo conectar con el servidor.');
    }
}

async function selectPlaylist(playlistId, playlistName) {
    playlistsEl.innerHTML = '';
    recommendationsEl.innerHTML = '';
    refreshBtn.hidden = true;
    showMessage('Guardando playlist...');

    try {
        const response = await fetch(`/playlists/select?playlistId=${encodeURIComponent(playlistId)}`, {
            method: 'POST'
        });
        if (response.status === 401) {
            await handleSessionExpired();
            return;
        }
        if (!response.ok) {
            showMessage(`No se pudo guardar la playlist (${response.status}).`);
            return;
        }
        showToast(`Playlist "${playlistName}" seleccionada`);
        await pollRecommendations();
    } catch (err) {
        showMessage('No se pudo conectar con el servidor.');
    }
}

async function pollRecommendations() {
    const maxAttempts = 15;
    const intervalMs = 3000;

    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
        showMessage(`Generando recomendaciones... (${attempt}/${maxAttempts})`);
        const result = await fetchRecommendations();
        if (result.status === 'unauthorized') {
            await handleSessionExpired();
            return;
        }
        if (result.status === 'error') {
            showMessage('Error al cargar recomendaciones.');
            return;
        }
        if (result.status === 'ok' && result.data.length > 0) {
            renderRecommendations(result.data);
            return;
        }
        await new Promise(resolve => setTimeout(resolve, intervalMs));
    }

    showMessage('Todavía no hay recomendaciones generadas. Esperá un poco más y probá "Actualizar".');
    refreshBtn.hidden = false;
}

async function fetchRecommendations() {
    try {
        const response = await fetch('/recommendations');
        if (response.status === 401) return { status: 'unauthorized' };
        if (response.status === 404) return { status: 'no-playlist' };
        if (!response.ok) return { status: 'error' };
        return { status: 'ok', data: await response.json() };
    } catch (err) {
        return { status: 'error' };
    }
}

function renderRecommendations(recommendations) {
    showMessage(`Últimas ${recommendations.length} recomendación(es)`);
    recommendationsEl.innerHTML = recommendations.map(renderRecommendation).join('');
    refreshBtn.hidden = false;
}

async function loadRecommendations() {
    playlistsEl.innerHTML = '';
    recommendationsEl.innerHTML = '';
    refreshBtn.hidden = true;
    showMessage('Cargando...');

    const result = await fetchRecommendations();
    if (result.status === 'unauthorized') {
        await handleSessionExpired();
        return;
    }
    if (result.status === 'no-playlist') {
        showMessage('Todavía no elegiste una playlist. Usá "Elegir playlist" primero.');
        return;
    }
    if (result.status === 'error') {
        showMessage('No se pudo conectar con el servidor.');
        return;
    }
    if (result.data.length === 0) {
        showMessage('Todavía no hay recomendaciones generadas. Esperá unos minutos y volvé a probar.');
        refreshBtn.hidden = false;
        return;
    }

    renderRecommendations(result.data);
}

function renderPlaylistCard(playlist) {
    const image = playlist.imageUrl
        ? `<img src="${escapeHtml(playlist.imageUrl)}" alt="">`
        : '<div class="card-image-placeholder"></div>';
    return `
        <li class="card" data-playlist-id="${escapeHtml(playlist.id)}" data-playlist-name="${escapeHtml(playlist.name)}">
            ${image}
            <div>
                <div class="card-title">${escapeHtml(playlist.name)}</div>
                <div class="card-subtitle">${playlist.trackCount} track(s)</div>
            </div>
        </li>
    `;
}

function renderRecommendation(rec) {
    const scorePercent = Math.round(rec.matchScore * 100);
    const createdAt = new Date(rec.createdAt).toLocaleString();
    const image = rec.albumImageUrl
        ? `<img src="${escapeHtml(rec.albumImageUrl)}" alt="">`
        : '<div class="card-image-placeholder"></div>';
    const trackId = encodeURIComponent(rec.recommendedTrackId);
    const trackUrl = `https://open.spotify.com/track/${trackId}`;
    const trackUri = `spotify:track:${trackId}`;
    return `
        <li class="card rec-card">
            <a class="play-btn" href="${trackUrl}" data-spotify-uri="${trackUri}" data-track-url="${trackUrl}" rel="noopener noreferrer" title="Escuchar en Spotify">▶</a>
            ${image}
            <div>
                <div class="card-title">${escapeHtml(rec.recommendedTrackName)}</div>
                <div class="card-subtitle">${escapeHtml(rec.artistName)}</div>
                <div class="rec-meta">
                    <span class="rec-score">${scorePercent}% match</span>
                    ${rec.genre ? `<span>${escapeHtml(rec.genre)}</span>` : ''}
                    <span>por escuchar "${escapeHtml(rec.sourceTrackName)}" de ${escapeHtml(rec.sourceArtistName)}</span>
                    <span>${createdAt}</span>
                </div>
            </div>
        </li>
    `;
}

function showMessage(text) {
    messageEl.textContent = text;
}

function showToast(text) {
    toastEl.textContent = text;
    toastEl.hidden = false;
    toastEl.classList.add('toast-visible');
    if (toastTimeoutId) clearTimeout(toastTimeoutId);
    toastTimeoutId = setTimeout(() => {
        toastEl.classList.remove('toast-visible');
        toastEl.hidden = true;
    }, 3500);
}

function escapeHtml(value) {
    const div = document.createElement('div');
    div.textContent = value ?? '';
    return div.innerHTML;
}
