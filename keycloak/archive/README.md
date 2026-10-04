# Archived: the old local Keycloak setup

Kept for reference only; they no longer work against the local cluster.

They provisioned Keycloak by script (clients such as `payment-service`, `seller-api-*`, role `SELLER_API`) and fetched
tokens for those clients. That setup was replaced on 2026-10-02 by:

- `keycloak/setup-keycloak.sh`: loads `keycloak/realm/ecommerce-platform.json` and `keycloak/realm/merchants-seed.json`
  (and removes the old clients and role);
- `keycloak/get-access-token.sh`: a token for a merchant's backend or a person.

The Azure scripts (`keycloak/get-token-azure.sh`, `keycloak/provision-keycloak-azure.sh`) are not archived: the Azure
environment has not moved to the new setup yet.
