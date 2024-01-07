# ecommerce-product-service
I will develop an e commerce website backend (project is suggested by ChatGPT) to develop my azure, microservices and kubernetes experience
docker build -t product-service:0.0.1-SNAPSHOT -f docker/Dockerfile .
docker tag product-service:0.0.1-SNAPSHOT bmcnpnr/ecommerce-product-service:latest
docker push bmcnpnr/ecommerce-product-service:latest
