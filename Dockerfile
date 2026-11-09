# =====================================================================
#  AlertaLar - imagem para publicação (Google Cloud Run, Render, VPS...)
#  No Google Cloud Run, use o arquivo implantar-cloud-run.sh.
#  1º estágio: compila o back-end Java com Maven (inclui o front-end no JAR)
#  2º estágio: imagem enxuta só com o Java 21 e o JAR
# =====================================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
COPY backend/pom.xml backend/pom.xml
# Baixa as dependências primeiro (acelera builds seguintes)
RUN mvn -q -f backend/pom.xml dependency:go-offline
COPY backend/src backend/src
COPY frontend frontend
RUN mvn -q -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 alertalar
COPY --from=build /app/backend/target/alertalar.jar app.jar
USER alertalar
# Memória: 60% do contêiner para o heap e limites para o resto, para caber em
# planos gratuitos de 512 MB (medido: cerca de 320 MB em uso).
# TieredStopAtLevel=1 compila só no nível rápido do Java: liga mais depressa e
# gasta cerca de 30% menos CPU ao iniciar (importa em serviços que desligam
# quando ninguém usa e são cobrados ou limitados por CPU).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:ReservedCodeCacheSize=48m -XX:MaxMetaspaceSize=192m"
# A porta vem da variável PORT definida pelo provedor (padrão 8080)
EXPOSE 8080
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
