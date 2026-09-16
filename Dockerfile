# Mirrors the Dockerfile onno-cloud supplies, with one addition: the builder stage clears its own
# caches before it ends.
#
# Tenant images are built by kaniko, which snapshots the filesystem a stage leaves behind. A Gradle
# build leaves ~/.gradle, /src/build and the widgets' node_modules there — a few hundred megabytes
# across tens of thousands of small files — and kaniko needed over two gigabytes to hash it on a
# three-gigabyte node. Every build was evicted before it could push. Deleting them in the same RUN
# means they are gone by the time the snapshot is taken, and the jar has already been copied out.
FROM eclipse-temurin:21-jdk AS build
ARG ONNO_CORE_VERSION=
WORKDIR /src
COPY . .
RUN if [ -f onno-registry.init.gradle ]; then INIT="--init-script onno-registry.init.gradle"; else INIT=""; fi \
 && ./gradlew --no-daemon $INIT ${ONNO_CORE_VERSION:+-PonnoCoreVersion=$ONNO_CORE_VERSION} clean bootJar -x test \
 && cp "$(ls build/libs/*.jar | grep -v -- '-plain.jar' | head -1)" /src/app.jar \
 && rm -rf /root/.gradle /root/.npm /src/build /src/.gradle

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
