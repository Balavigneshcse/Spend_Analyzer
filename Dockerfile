# ---------- build ----------
FROM maven:3.9-eclipse-temurin-25-alpine AS build
WORKDIR /src

# Dependencies first, so this layer is reused when only the code changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests need a database and already ran before the push; the image build only packages.
RUN mvn -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/app.jar extract --layers --destination /out

# ---------- run ----------
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

COPY --from=build /out/dependencies/ ./
COPY --from=build /out/spring-boot-loader/ ./
COPY --from=build /out/snapshot-dependencies/ ./
COPY --from=build /out/application/ ./

USER app

ENV SPRING_PROFILES_ACTIVE=prod
# Sized for a 512 MB / 0.1 CPU container (Render free plan).
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -Xms48m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:TieredStopAtLevel=1 -XX:+UseCompactObjectHeaders -XX:+ExitOnOutOfMemoryError"

# Render sets PORT (10000 by default); the app listens on it.
EXPOSE 10000
ENTRYPOINT ["java", "-jar", "app.jar"]
