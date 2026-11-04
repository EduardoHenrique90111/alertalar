// Página de metodologia: situação real de cada fonte de dados.
import { api } from '../api.js';
import { dataHora, el, iniciarCabecalho, limpar } from '../ui.js';

iniciarCabecalho();

const SITUACAO = {
  ATIVA: 'Integrada',
  IMPORTACAO_MANUAL: 'Importação de planilha oficial',
  REQUER_CREDENCIAL: 'Não integrada: exige credencial',
  PLANEJADA: 'Não integrada: prevista',
};

async function carregar() {
  const tbody = document.getElementById('m-fontes');
  try {
    const fontes = await api('/api/fontes');
    limpar(tbody);
    fontes.forEach((f) => tbody.append(el('tr', {},
      el('td', {}, f.url ? el('a', { href: f.url, target: '_blank', rel: 'noopener' }, f.nome) : f.nome,
        f.oficial ? el('span', { class: 'selo selo-oficial' }, 'órgão público') : null),
      el('td', {}, SITUACAO[f.statusIntegracao] || f.statusIntegracao),
      el('td', {}, f.dataAtualizacao ? dataHora(f.dataAtualizacao) : 'Sem registro'),
      el('td', {}, f.descricao || '', f.atribuicao ? el('div', { class: 'dica' }, 'Atribuição: ' + f.atribuicao) : null))));
  } catch (e) {
    limpar(tbody);
    tbody.append(el('tr', {}, el('td', { colspan: '4' }, 'Não foi possível carregar as fontes: ' + e.message)));
  }
}

carregar();
