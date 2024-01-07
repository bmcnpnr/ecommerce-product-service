FROM openjdk:21-jdk

COPY ./target/product-service-*.jar /usr/app/app.jar

WORKDIR /usr/app

RUN sh -c 'touch app.jar'

ENTRYPOINT ["java", "-jar", "app.jar"]

EXPOSE 8081