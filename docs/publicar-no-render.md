# Publicar o AlertaLar no Render (gratuito, sem cartão)

O Render roda o AlertaLar a partir do `Dockerfile` e publica de novo sozinho a cada mudança no
GitHub. O plano gratuito não pede cartão. Em troca, o site **dorme depois de 15 minutos sem
visitas** e a primeira visita seguinte espera ele ligar de novo (de 1 a 3 minutos). O banco
continua no Neon.

## 1. Criar o serviço (uma vez)

1. Entre em **render.com** e clique em **Get Started**. Escolha **GitHub** para entrar com a sua
   conta do GitHub.
2. Clique em **New +** → **Blueprint**.
3. Em **Connect a repository**, clique em **Configure account** (ou **Connect GitHub**), permita
   o acesso ao repositório **alertalar** e volte. Escolha **EduardoHenrique90111/alertalar**.
4. Dê um nome ao Blueprint (por exemplo, `alertalar`). O Render lê o arquivo `render.yaml` do
   projeto e mostra o serviço **alertalar** no plano **Free**.
5. Ele pede o valor de **DATABASE_URL**. Cole a **connection string do Neon** (painel do Neon →
   **Connect** → copiar), no formato
   `postgresql://USUARIO:SENHA@ep-....neon.tech/neondb?sslmode=require`.
6. Clique em **Apply** (ou **Deploy Blueprint**). A primeira publicação leva de 5 a 15 minutos.
7. Quando aparecer **Live**, o endereço do site fica no topo da página do serviço, no formato
   `https://alertalar.onrender.com` (o Render acrescenta letras se o nome já estiver em uso).

Opcional: em **Environment**, acrescente `ALERTALAR_CONTATO_EMAIL` com um e-mail de contato,
pedido pela política de uso do OpenStreetMap.

## 2. Publicar versões novas

Automático: cada mudança no ramo `main` do GitHub gera uma publicação nova no Render.

## 3. Se a primeira visita demorar

É o site acordando. Para ele não dormir, dá para usar um serviço gratuito que visita o
endereço a cada 10 minutos (por exemplo, **cron-job.org**), apontando para
`https://SEU-ENDERECO.onrender.com/actuator/health`. Um serviço ligado o mês inteiro usa cerca
de 744 das 750 horas gratuitas do mês, então isso só cabe para um serviço gratuito por conta.

## 4. Desligar o Vercel

Depois que o site estiver no ar pelo Render, apague o projeto no Vercel
(**Settings → Advanced → Delete Project**) para ele parar de mandar avisos.

## Se algo der errado

- **Deploy falhou**: abra o serviço → **Logs**. Se aparecer erro de conexão com o banco, a
  connection string está errada: em **Environment**, corrija `DATABASE_URL` e clique em
  **Save, rebuild, and deploy**.
- **Memória**: o plano gratuito tem 512 MB; o `Dockerfile` já limita o Java para caber nisso.
