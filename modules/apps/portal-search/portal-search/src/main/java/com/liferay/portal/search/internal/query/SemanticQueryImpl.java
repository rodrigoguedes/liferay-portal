
package com.liferay.portal.search.internal.query;

import com.liferay.portal.search.query.SemanticQuery;
import com.liferay.portal.search.query.QueryVisitor;

public class SemanticQueryImpl extends BaseQueryImpl implements SemanticQuery {

    public SemanticQueryImpl(String field, String query) {
        _field = field;
        _query = query;
    }

    @Override
    public <T> T accept(QueryVisitor<T> queryVisitor) {
        return queryVisitor.visit(this);
    }

    @Override
    public String getField() {
        return _field;
    }

    @Override
    public String getQuery() {
        return _query;
    }


    private final String _field;
    private final String _query;

}
