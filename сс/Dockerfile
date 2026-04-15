FROM harbor.online.tkbbank.ru/custom-base-images/openjre-alpine-musl:17.0.10
COPY deploy/*.jar /usr/local/lib/app.jar
EXPOSE 8080
ENTRYPOINT [ "sh", "-c", "java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -Duser.timezone=GMT+3 -Dspring.profiles.active=$APP_PROF -jar /usr/local/lib/app.jar" ]