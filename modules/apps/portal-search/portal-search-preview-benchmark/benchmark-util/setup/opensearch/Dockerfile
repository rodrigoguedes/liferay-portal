# LPD-98298 — OpenSearch 2.19 with the analysis plugins Liferay's index
# mappings require (same reason the local ES uses the custom
# elasticsearch-analysis-plugins image). Baking them into the image keeps
# `docker compose up --force-recreate` from losing them.

FROM opensearchproject/opensearch:2.19.2

RUN bin/opensearch-plugin install --batch analysis-icu && \
	bin/opensearch-plugin install --batch analysis-kuromoji && \
	bin/opensearch-plugin install --batch analysis-smartcn && \
	bin/opensearch-plugin install --batch analysis-stempel
