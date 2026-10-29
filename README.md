# ecommerce-product-service

The source of truth for products, categories and stock. Storefront reads are
public. order-service reserves and releases stock through
`PATCH /api/v1/products/{id}/stock` with `{"delta": n}`. catalog-service and
review-service read products by ID. Deleting a product is soft
(`status = INACTIVE`), and listings show `ACTIVE` products only.

| | |
|---|---|
| Port | 8082 |
| Gateway routes | `/api/v1/products/**`, `/api/v1/categories/**` (`GET` is public) |
| Data store | PostgreSQL `productdb` (`categories`, `products`, Flyway) |
| Called by | order-service, catalog-service and review-service (Feign), and the frontend |
| Docs | [Deep dive](https://github.com/bmcnpnr/ecommerce-platform/blob/develop/docs/services/product-service.md) · [API reference](https://github.com/bmcnpnr/ecommerce-platform/blob/develop/docs/api/product-service.md) · Swagger UI at http://localhost:8080/swagger-ui.html (`product-service`) · [All docs](https://github.com/bmcnpnr/ecommerce-platform/blob/develop/docs/README.md) |

With the repositories checked out side by side, the same docs are in
`../ecommerce-platform/docs/`.

> **Known gaps** (see the deep dive): write endpoints have no role check, and
> the stock update is a read-modify-write without locking, so concurrent
> buyers can oversell.

## Build and run

```bash
mvn -B verify                       # unit, slice and contract tests (H2)
mvn -B clean package -DskipTests    # the jar the Docker image copies
cd ../ecommerce-platform && docker compose up -d --build product-service
```

## Contract tests (Pact)

`src/test/java/.../contract/ProductServiceProviderPactTest` is the **provider**
for order-service, catalog-service and review-service (Feign). It replays the
pacts in `src/test/resources/pacts/` against the real application on H2, on a
random port. The provider states `product 1 exists`, `product 999 does not exist`
and `categories exist` are set up with SQL, because the consumers address
products by ID.

Run it alone with `mvn test -Dtest='*PactTest'`. Redistribute pacts with
`ecommerce-platform/sync-pacts.sh`.
