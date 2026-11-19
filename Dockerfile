# MedSol — image de production (voir DEPLOIEMENT.md)
# Étape 1 : compilation (les dépendances sont mises en cache tant que pom.xml ne change pas)
FROM maven:3.9-eclipse-temurin-21 AS compilation
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests package && cp target/medsol-*.jar /medsol.jar

# Étape 2 : exécution, JRE seul, utilisateur sans privilège
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 medsol && useradd --system --uid 10001 --gid medsol --no-create-home medsol
COPY --from=compilation /medsol.jar /app/medsol.jar
USER medsol
ENV SPRING_PROFILES_ACTIVE=prod \
    TZ=UTC \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/medsol.jar"]
