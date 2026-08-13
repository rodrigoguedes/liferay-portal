# LPD-98298 — Elasticsearch 8.19 with the analysis plugins Liferay's index
# mappings require (equivalent to the local elasticsearch-analysis-plugins
# image). Baking them into the image keeps
# `docker compose up --force-recreate` from losing them.

FROM docker.elastic.co/elasticsearch/elasticsearch:8.19.19

RUN bin/elasticsearch-plugin install --batch analysis-icu && \
	bin/elasticsearch-plugin install --batch analysis-kuromoji && \
	bin/elasticsearch-plugin install --batch analysis-smartcn && \
	bin/elasticsearch-plugin install --batch analysis-stempel
