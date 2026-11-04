// Cliente da API REST do AlertaLar (mesma origem). Envia o token CSRF nas requisições
// que alteram dados e converte respostas de erro (Problem Details) em ErroApi.

export class ErroApi extends Error {
  constructor(status, problema) {
    super((problema && (problema.detail || problema.title)) || mensagemPadrao(status));
    this.status = status;
    this.problema = problema || {};
    this.erros = (problema && problema.erros) || {};
  }
}

function mensagemPadrao(status) {
  if (status === 0) return 'Sem conexão com o servidor. Verifique sua internet e tente novamente.';
  if (status === 401) return 'Faça login para continuar.';
  if (status === 403) return 'Você não tem permissão para esta ação.';
  if (status === 404) return 'Não encontrado.';
  if (status === 429) return 'Muitas solicitações em pouco tempo. Aguarde um instante.';
  if (status >= 500) return 'O servidor não conseguiu concluir a solicitação. Tente novamente em instantes.';
  return 'Não foi possível concluir a solicitação.';
}

function lerCookie(nome) {
  const item = document.cookie.split('; ').find((c) => c.startsWith(nome + '='));
  return item ? decodeURIComponent(item.substring(nome.length + 1)) : null;
}

async function garantirTokenCsrf() {
  if (!lerCookie('XSRF-TOKEN')) {
    await fetch('/api/auth/csrf', { credentials: 'same-origin', headers: { Accept: 'application/json' } });
  }
  return lerCookie('XSRF-TOKEN') || '';
}

/**
 * @param {string} caminho  ex.: "/api/consultas"
 * @param {{metodo?: string, corpo?: any, formulario?: FormData, params?: object}} opcoes
 */
export async function api(caminho, { metodo = 'GET', corpo, formulario, params } = {}) {
  let url = caminho;
  if (params) {
    const p = new URLSearchParams();
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== '') p.append(k, v);
    });
    const qs = p.toString();
    if (qs) url += (url.includes('?') ? '&' : '?') + qs;
  }
  const headers = { Accept: 'application/json' };
  const opcoes = { method: metodo, headers, credentials: 'same-origin' };
  if (metodo !== 'GET') {
    headers['X-XSRF-TOKEN'] = await garantirTokenCsrf();
  }
  if (corpo !== undefined) {
    headers['Content-Type'] = 'application/json';
    opcoes.body = JSON.stringify(corpo);
  } else if (formulario) {
    opcoes.body = formulario;
  }
  let resposta;
  try {
    resposta = await fetch(url, opcoes);
  } catch {
    throw new ErroApi(0, null);
  }
  if (resposta.status === 204) return null;
  const tipo = resposta.headers.get('content-type') || '';
  const dados = tipo.includes('json') ? await resposta.json().catch(() => null) : null;
  if (!resposta.ok) throw new ErroApi(resposta.status, dados);
  return dados;
}
