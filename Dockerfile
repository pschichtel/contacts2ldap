FROM mozilla/sbt:latest AS build

RUN mkdir /build

WORKDIR /build

COPY build.sbt /build/
COPY project /build/project/

RUN sbt -no-colors update

ADD . /build/

RUN sbt -no-colors assembly

FROM adoptopenjdk/openjdk13:alpine-slim

ARG USER=contacts2ldap

RUN adduser -S "${USER}" \
 && mkdir /app \
 && chown "${USER}" /app

USER "${USER}"

WORKDIR /app

COPY --from=build "/build/target/scala-*/contacts2ldap.jar" /app/

ENTRYPOINT ["java", "-jar", "contacts2ldap.jar"]

CMD ["/config.json"]