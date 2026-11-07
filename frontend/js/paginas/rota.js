// Rota segura: ponto de partida (localização, clique ou endereço) → caminho até terreno mais alto.
/* global L */
import { api } from '../api.js';
import { el, iniciarCabecalho, limpar, mensagem, preencherUfs, numero } from '../ui.js';
import { criarMapa, icone, popup } from '../mapa.js';

iniciarCabecalho();

const $ = (id) => document.getElementById(id);
const msg = $('msg-rota');
const botaoCalcular = $('botao-calcular');
const partidaTexto = $('partida-texto');
const formBusca = $('form-busca');
preencherUfs(formBusca.estado);

const mapa = criarMapa($('mapa-rota'));
const camadaResultado = L.layerGroup().addTo(mapa);
let marcadorPartida = null;
let modo = 'A_PE';
let ultimaRota = null;

// ---------------- Onde a pessoa está (ponto azul) ----------------
// Atualizado continuamente enquanto a página está aberta, para acompanhar o caminho.
let pontoUsuario = null;
let circuloPrecisao = null;
let observador = null;

function mostrarUsuario(posicao) {
  const ll = L.latLng(posicao.coords.latitude, posicao.coords.longitude);
  const raio = Math.min(posicao.coords.accuracy || 0, 1500);
  if (!pontoUsuario) {
    circuloPrecisao = L.circle(ll, { radius: raio, className: 'precisao-usuario', interactive: false }).addTo(mapa);
    pontoUsuario = L.marker(ll, { icon: icone('usuario'), title: 'Você está aqui', keyboard: false, zIndexOffset: 1200 })
      .bindTooltip('Você está aqui').addTo(mapa);
  } else {
    pontoUsuario.setLatLng(ll);
    circuloPrecisao.setLatLng(ll).setRadius(raio);
  }
  return ll;
}

function acompanharUsuario() {
  if (observador !== null || !navigator.geolocation) return;
  observador = navigator.geolocation.watchPosition(mostrarUsuario, (erro) => {
    if (erro.code === erro.PERMISSION_DENIED) {
      navigator.geolocation.clearWatch(observador);
      observador = null;
    }
  }, { enableHighAccuracy: true, maximumAge: 10000, timeout: 30000 });
}

// Botão no canto do mapa: centraliza no ponto azul (pede a localização se ainda não tiver)
const ControleLocalizacao = L.Control.extend({
  options: { position: 'topleft' },
  onAdd() {
    const caixa = L.DomUtil.create('div', 'leaflet-bar controle-localizacao');
    const botao = L.DomUtil.create('button', '', caixa);
    botao.type = 'button';
    botao.title = 'Mostrar onde estou';
    botao.setAttribute('aria-label', 'Mostrar onde estou no mapa');
    const ns = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    [['circle', { cx: 12, cy: 12, r: 4, class: 'mira-centro' }], ['circle', { cx: 12, cy: 12, r: 8, class: 'mira-anel' }],
      ['path', { d: 'M12 1v4M12 19v4M1 12h4M19 12h4', class: 'mira-anel' }]].forEach(([tag, attrs]) => {
      const n = document.createElementNS(ns, tag);
      Object.entries(attrs).forEach(([k, v]) => n.setAttribute(k, v));
      svg.append(n);
    });
    botao.append(svg);
    L.DomEvent.disableClickPropagation(caixa);
    L.DomEvent.on(botao, 'click', () => centralizarNoUsuario(botao));
    return caixa;
  },
});
mapa.addControl(new ControleLocalizacao());

function centralizarNoUsuario(botao) {
  limpar(msg);
  if (pontoUsuario) {
    mapa.setView(pontoUsuario.getLatLng(), Math.max(mapa.getZoom(), 16));
    return;
  }
  if (!navigator.geolocation) {
    mensagem(msg, 'atencao', 'Este navegador não informa a localização.');
    return;
  }
  botao.disabled = true;
  navigator.geolocation.getCurrentPosition((p) => {
    botao.disabled = false;
    mapa.setView(mostrarUsuario(p), 16);
    acompanharUsuario();
  }, () => {
    botao.disabled = false;
    mensagem(msg, 'atencao', 'Não foi possível obter sua localização. Verifique se o navegador tem permissão para acessá-la.');
  }, { enableHighAccuracy: true, timeout: 15000, maximumAge: 60000 });
}

// Se a pessoa já autorizou a localização antes, o ponto azul aparece sem perguntar de novo
if (navigator.permissions && navigator.geolocation) {
  navigator.permissions.query({ name: 'geolocation' })
    .then((estado) => { if (estado.state === 'granted') acompanharUsuario(); })
    .catch(() => {});
}

// ---------------- Ponto de partida ----------------
let temporizadorRecalculo = null;

/**
 * Muda o ponto de partida. Se já havia uma rota na tela, ela é apagada na hora (nunca fica um
 * caminho saindo do ponto antigo) e uma nova é calculada a partir do novo ponto.
 */
function definirPartida(latlng, origem, descricao) {
  if (!marcadorPartida) {
    marcadorPartida = L.marker(latlng, { icon: icone('imovel'), draggable: true, title: 'Ponto de partida', zIndexOffset: 1000 }).addTo(mapa);
    marcadorPartida.on('dragend', () => definirPartida(marcadorPartida.getLatLng(), 'mapa'));
  } else {
    marcadorPartida.setLatLng(latlng);
  }
  const textoOrigem = descricao || { gps: 'sua localização', mapa: 'ponto marcado no mapa', endereco: 'endereço encontrado', link: 'endereço da consulta' }[origem] || 'ponto marcado';
  partidaTexto.textContent = `Ponto de partida: ${textoOrigem} (${latlng.lat.toFixed(5).replace('.', ',')}, ${latlng.lng.toFixed(5).replace('.', ',')}).`;
  botaoCalcular.disabled = false;
  if (ultimaRota) {
    apagarRota();
    clearTimeout(temporizadorRecalculo);
    temporizadorRecalculo = setTimeout(calcular, 500);
  }
}

function apagarRota() {
  camadaResultado.clearLayers();
  $('resultado-rota').classList.add('oculto');
  ultimaRota = null;
}

/** Nome curto de um resultado do Nominatim: "Canoas Shopping, 6750, Avenida Guilherme Schell" */
function nomeCurto(enderecoEncontrado) {
  return (enderecoEncontrado || '').split(',').slice(0, 3).map((t) => t.trim()).filter(Boolean).join(', ');
}

mapa.on('click', (ev) => {
  definirPartida(ev.latlng, 'mapa');
  limpar(msg);
});

$('botao-local').addEventListener('click', () => {
  limpar(msg);
  if (!navigator.geolocation) {
    mensagem(msg, 'atencao', 'Este navegador não informa a localização. Clique no mapa onde você está ou busque um endereço.');
    return;
  }
  const botao = $('botao-local');
  botao.disabled = true;
  botao.textContent = 'Obtendo localização…';
  navigator.geolocation.getCurrentPosition(
    (p) => {
      const ll = mostrarUsuario(p);
      acompanharUsuario();
      mapa.setView(ll, 16);
      definirPartida(ll, 'gps');
      botao.disabled = false;
      botao.textContent = 'Usar minha localização';
      calcular();
    },
    () => {
      botao.disabled = false;
      botao.textContent = 'Usar minha localização';
      mensagem(msg, 'atencao', 'Não foi possível obter sua localização (permissão negada ou sinal fraco). Clique no mapa onde você está ou busque um endereço.');
    },
    { enableHighAccuracy: true, timeout: 15000, maximumAge: 60000 });
});

formBusca.addEventListener('submit', async (ev) => {
  ev.preventDefault();
  limpar(msg);
  const rua = formBusca.rua.value.trim();
  const cidade = formBusca.cidade.value.trim();
  const estado = formBusca.estado.value;
  if (!rua || !cidade || !estado) {
    mensagem(msg, 'atencao', 'Para buscar, informe o endereço ou o nome do local, a cidade e o estado.');
    return;
  }
  limpar($('b-opcoes'));
  // "Rua das Flores, 120" → rua e número separados
  const m = rua.match(/^(.*?)[,\s]+(\d+[A-Za-z]?)\s*$/);
  const params = { rua: m ? m[1] : rua, numero: m ? m[2] : '', cidade, estado };
  const botao = $('botao-buscar');
  botao.disabled = true;
  try {
    const r = await api('/api/enderecos/buscar', { params });
    escolherCandidato(r.candidatos[0]);
    // Mais de um lugar com esse nome (ex.: "Shopping"): deixa a pessoa escolher o certo
    const outros = r.candidatos.slice(1).filter((c, i, todos) => todos.findIndex((o) => nomeCurto(o.enderecoEncontrado) === nomeCurto(c.enderecoEncontrado)) === i
      && nomeCurto(c.enderecoEncontrado) !== nomeCurto(r.candidatos[0].enderecoEncontrado));
    const opcoes = limpar($('b-opcoes'));
    if (outros.length) {
      opcoes.append(el('p', { class: 'dica' }, 'Não é esse lugar? Escolha outro resultado:'),
        el('ul', { class: 'opcoes-busca' }, outros.map((c) => {
          const botao = el('button', { type: 'button', class: 'opcao-busca' }, nomeCurto(c.enderecoEncontrado));
          botao.addEventListener('click', () => {
            escolherCandidato(c);
            opcoes.querySelectorAll('.opcao-busca').forEach((b) => b.removeAttribute('aria-current'));
            botao.setAttribute('aria-current', 'true');
          });
          return el('li', {}, botao);
        })));
    }
  } catch (e) {
    mensagem(msg, 'erro', e.message);
  } finally {
    botao.disabled = false;
  }
});

document.querySelectorAll('[data-modo]').forEach((b) => b.addEventListener('click', () => {
  modo = b.dataset.modo;
  document.querySelectorAll('[data-modo]').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
  if (ultimaRota) calcular();
}));

botaoCalcular.addEventListener('click', calcular);

function escolherCandidato(c) {
  limpar(msg);
  const ll = L.latLng(c.latitude, c.longitude);
  mapa.setView(ll, 17);
  definirPartida(ll, 'endereco', nomeCurto(c.enderecoEncontrado));
  if (!c.suficienteParaAnalise) {
    mensagem(msg, 'atencao', 'O lugar foi encontrado só de forma aproximada. Arraste o marcador até o ponto exato.');
  }
}

// ---------------- Cálculo ----------------
async function calcular() {
  clearTimeout(temporizadorRecalculo);
  if (!marcadorPartida) return;
  limpar(msg);
  const p = marcadorPartida.getLatLng();
  botaoCalcular.disabled = true;
  botaoCalcular.textContent = 'Calculando…';
  mensagem(msg, 'info', 'Analisando a altura do terreno ao redor e as áreas de risco. Pode levar alguns segundos.');
  try {
    const r = await api('/api/rotas/segura', { params: { lat: p.lat.toFixed(6), lon: p.lng.toFixed(6), modo } });
    ultimaRota = r;
    limpar(msg);
    renderizar(r);
  } catch (e) {
    mensagem(msg, 'erro', e.message);
  } finally {
    botaoCalcular.disabled = false;
    botaoCalcular.textContent = 'Calcular rota segura';
  }
}

function renderizar(r) {
  camadaResultado.clearLayers();
  const limites = [];
  (r.setoresRisco || []).forEach((s) => s.aneis.forEach((anel) => {
    L.polygon(anel, { color: '#c8372d', weight: 2, fillColor: '#c8372d', fillOpacity: 0.2 })
      .bindPopup(popup('Área de risco ' + (s.numero || ''), s.grau ? 'Grau: ' + s.grau : null, s.tipologia, 'Fonte: Serviço Geológico do Brasil'))
      .addTo(camadaResultado);
  }));
  limites.push([r.origem.latitude, r.origem.longitude]);

  if (r.trajeto) {
    L.polyline(r.trajeto.coordenadas, { color: '#ffffff', weight: 9, opacity: 0.9, interactive: false }).addTo(camadaResultado);
    L.polyline(r.trajeto.coordenadas, { color: '#12355b', weight: 5 }).bindTooltip('Caminho sugerido', { sticky: true }).addTo(camadaResultado);
    r.trajeto.coordenadas.forEach((c) => limites.push(c));
  }
  if (r.destino) {
    L.marker([r.destino.latitude, r.destino.longitude], { icon: icone('seguro'), title: 'Terreno mais alto', zIndexOffset: 900 })
      .bindPopup(popup('Terreno mais alto', `Cerca de ${Math.round(r.destino.ganhoElevacao)} m acima da partida`, `Altitude aproximada: ${Math.round(r.destino.elevacao)} m`))
      .addTo(camadaResultado);
    limites.push([r.destino.latitude, r.destino.longitude]);
  }
  (r.alternativas || []).forEach((a) => {
    L.marker([a.latitude, a.longitude], { icon: icone('alternativa'), title: 'Outro ponto alto próximo' })
      .bindPopup(popup('Outro ponto mais alto', a.elevacao !== null ? `Altitude aproximada: ${Math.round(a.elevacao)} m` : null))
      .addTo(camadaResultado);
  });
  const baixo = r.perfil && r.origem.elevacao !== null && r.perfil.elevacaoMinima < r.origem.elevacao - 2 && r.perfil.distanciaPontoMaisBaixo > 50;
  if (baixo) {
    L.marker(r.perfil.pontoMaisBaixo, { icon: icone('relato'), title: 'Trecho mais baixo do caminho' })
      .bindPopup(popup('Trecho mais baixo do caminho', `${Math.round(r.origem.elevacao - r.perfil.elevacaoMinima)} m abaixo da partida`, 'Não atravesse se estiver alagado'))
      .addTo(camadaResultado);
  }
  if (limites.length > 1) mapa.fitBounds(limites, { padding: [40, 40], maxZoom: 17 });
  else mapa.setView(limites[0], 16);

  const secao = $('resultado-rota');
  secao.classList.remove('oculto');
  $('t-resultado-rota').textContent = r.status === 'ROTA_ENCONTRADA' ? 'Rota sugerida' : 'Resultado';
  $('r-mensagem').textContent = r.mensagem;
  const medidas = limpar($('r-medidas'));
  if (r.trajeto) {
    medidas.append(
      medida(distancia(r.trajeto.distanciaMetros), 'de caminho'),
      medida(tempo(r.trajeto.duracaoSegundos), r.modo === 'A_PE' ? 'a pé' : 'de carro'),
      medida('+' + Math.round(r.destino.ganhoElevacao) + ' m', 'de altura em relação à partida'));
  }
  $('r-destino').textContent = r.destino ? r.destino.descricao : '';

  desenharPerfil(r);

  const avisos = limpar($('r-avisos'));
  r.avisos.forEach((a, i) => {
    const destaque = i < r.avisos.length - 2; // os dois últimos são orientações gerais
    avisos.append(el('div', { class: 'mensagem ' + (destaque ? 'mensagem-atencao' : '') }, el('p', {}, a)));
  });

  const lista = limpar($('r-instrucoes'));
  if (r.trajeto) {
    r.trajeto.instrucoes.forEach((i) => lista.append(el('li', {}, i.texto,
      i.distanciaMetros > 0 ? el('span', { class: 'dica' }, ' ' + distancia(i.distanciaMetros)) : null)));
  } else {
    lista.append(el('li', { class: 'vazio' }, 'Sem caminho calculado.'));
  }

  const abrir = $('r-abrir-mapas');
  if (r.destino) {
    const modoGoogle = r.modo === 'A_PE' ? 'walking' : 'driving';
    abrir.href = `https://www.google.com/maps/dir/?api=1&origin=${r.origem.latitude},${r.origem.longitude}&destination=${r.destino.latitude},${r.destino.longitude}&travelmode=${modoGoogle}`;
    abrir.classList.remove('oculto');
  } else {
    abrir.classList.add('oculto');
  }
  const fontes = limpar($('r-fontes'));
  r.fontes.forEach((f) => fontes.append(el('li', {}, f)));
  secao.focus({ preventScroll: true });
  secao.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'start' });
}

// Perfil de elevação em SVG (sem bibliotecas): linha do terreno e referência da altura da partida.
function desenharPerfil(r) {
  const bloco = $('r-perfil-bloco');
  const alvo = limpar($('r-perfil'));
  if (!r.perfil || !r.perfil.amostras || r.perfil.amostras.length < 2) {
    bloco.classList.add('oculto');
    return;
  }
  bloco.classList.remove('oculto');
  const ns = 'http://www.w3.org/2000/svg';
  // Largura real do bloco (em px) para o texto do gráfico não encolher no celular
  const L_ = Math.round(Math.min(720, Math.max(300, alvo.clientWidth - 16))); const A = 160; const M = { e: 44, d: 8, t: 10, b: 24 };
  const pts = r.perfil.amostras;
  const dMax = pts[pts.length - 1][0] || 1;
  const e0 = r.origem.elevacao;
  let eMin = Math.min(r.perfil.elevacaoMinima, e0);
  let eMax = Math.max(r.perfil.elevacaoMaxima, e0);
  if (eMax - eMin < 10) { eMax += 5; eMin -= 5; }
  const x = (d) => M.e + (d / dMax) * (L_ - M.e - M.d);
  const y = (e) => M.t + (1 - (e - eMin) / (eMax - eMin)) * (A - M.t - M.b);
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', `0 0 ${L_} ${A}`);
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', `Perfil de altura do caminho: de ${Math.round(pts[0][1])} m na partida a ${Math.round(pts[pts.length - 1][1])} m no destino, ponto mais baixo ${Math.round(r.perfil.elevacaoMinima)} m.`);
  const no = (tag, attrs) => { const n = document.createElementNS(ns, tag); Object.entries(attrs).forEach(([k, v]) => n.setAttribute(k, v)); svg.append(n); return n; };
  const area = [`M${x(pts[0][0])},${A - M.b}`].concat(pts.map((p) => `L${x(p[0]).toFixed(1)},${y(p[1]).toFixed(1)}`)).concat([`L${x(dMax)},${A - M.b}`, 'Z']).join(' ');
  no('path', { d: area, class: 'perfil-area' });
  no('polyline', { points: pts.map((p) => `${x(p[0]).toFixed(1)},${y(p[1]).toFixed(1)}`).join(' '), class: 'perfil-linha' });
  no('line', { x1: M.e, x2: L_ - M.d, y1: y(e0), y2: y(e0), class: 'perfil-referencia' });
  const rotulo = (tx, px, py, anchor = 'start') => { const t = no('text', { x: px, y: py, 'text-anchor': anchor, class: 'perfil-texto' }); t.textContent = tx; };
  rotulo(`${Math.round(eMax)} m`, M.e - 6, M.t + 8, 'end');
  rotulo(`${Math.round(eMin)} m`, M.e - 6, A - M.b, 'end');
  rotulo('partida', M.e, A - 6);
  rotulo(distancia(dMax), L_ - M.d, A - 6, 'end');
  alvo.append(svg);
  $('r-perfil-legenda').textContent = `Linha tracejada: altura do ponto de partida (${Math.round(e0)} m). Fonte: Copernicus DEM GLO-90, com margem de erro de alguns metros.`;
}

function medida(valor, rotulo) {
  return el('div', { class: 'medida' }, el('strong', {}, valor), el('span', {}, rotulo));
}
function distancia(m) {
  return m < 1000 ? `${Math.round(m / 10) * 10} m` : `${numero(m / 1000)} km`;
}
function tempo(s) {
  const min = Math.max(1, Math.round(s / 60));
  return min < 60 ? `${min} min` : `${Math.floor(min / 60)} h ${min % 60} min`;
}

// Vindo do resultado de uma consulta: /rota.html?lat=..&lon=..
const params = new URLSearchParams(location.search);
const latP = parseFloat(params.get('lat'));
const lonP = parseFloat(params.get('lon'));
if (!Number.isNaN(latP) && !Number.isNaN(lonP)) {
  const ll = L.latLng(latP, lonP);
  mapa.setView(ll, 16);
  definirPartida(ll, 'link');
  calcular();
}
