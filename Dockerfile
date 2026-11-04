# =====================================================================
#  AlertaLar - imagem para publicação (Render, Railway, Koyeb, VPS...)
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
# Usa no máximo 75% da memória do contêiner (planos gratuitos costumam ter 512 MB)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
# A porta vem da variável PORT definida pelo provedor (padrão 8080)
EXPOSE 8080
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
