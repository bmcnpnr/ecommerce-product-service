# ecommerce-product-service
I will develop an e commerce website backend (project is suggested by ChatGPT) to develop my azure, microservices and kubernetes experience
docker build -t product-service:0.0.1-SNAPSHOT -f docker/Dockerfile .
docker tag product-service:0.0.1-SNAPSHOT bmcnpnr/ecommerce-product-service:latest
docker push bmcnpnr/ecommerce-product-service:latest

## Contract tests (Pact)

- `src/test/java/.../contract/ProductServiceProviderPactTest` — **provider** for order-service, catalog-service and review-service (Feign). Replays the pacts in `src/test/resources/pacts/` against the real application on H2 (random port). Provider states (`product 1 exists`, `product 999 does not exist`, `categories exist`) are set up with SQL because consumers address products by id.

Run them alone with `mvn test -Dtest='*PactTest'`; they are ordinary Surefire tests, so `mvn verify` and CI run them too. Regenerate and redistribute pacts across repositories with `ecommerce-platform/sync-pacts.sh` (see its README, "Contract tests").
