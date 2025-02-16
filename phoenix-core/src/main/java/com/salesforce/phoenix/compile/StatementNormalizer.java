/*******************************************************************************
 * Copyright (c) 2013, Salesforce.com, Inc.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 *     Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *     Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *     Neither the name of Salesforce.com nor the names of its contributors may 
 *     be used to endorse or promote products derived from this software without 
 *     specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE 
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL 
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR 
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER 
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, 
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE 
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package com.salesforce.phoenix.compile;

import java.sql.SQLException;
import java.util.List;

import com.google.common.collect.Lists;
import com.salesforce.phoenix.parse.BetweenParseNode;
import com.salesforce.phoenix.parse.ColumnParseNode;
import com.salesforce.phoenix.parse.ComparisonParseNode;
import com.salesforce.phoenix.parse.LessThanOrEqualParseNode;
import com.salesforce.phoenix.parse.ParseNode;
import com.salesforce.phoenix.parse.ParseNodeRewriter;
import com.salesforce.phoenix.parse.SelectStatement;
import com.salesforce.phoenix.util.SchemaUtil;


/**
 * 
 * Class that creates a new select statement ensuring that a literal always occurs
 * on the RHS (i.e. if literal found on the LHS, then the operator is reversed and
 * the literal is put on the RHS)
 *
 * @author jtaylor
 * @since 0.1
 */
public class StatementNormalizer extends ParseNodeRewriter {
    private boolean useFullNameForAlias;
    
    public StatementNormalizer(ColumnResolver resolver, int expectedAliasCount, boolean useFullNameForAlias) {
        super(resolver, expectedAliasCount);
        this.useFullNameForAlias = useFullNameForAlias;
    }

    public static ParseNode normalize(ParseNode where, ColumnResolver resolver) throws SQLException {
        return rewrite(where, new StatementNormalizer(resolver, 0, false));
    }
    
    /**
     * Rewrite the select statement by switching any constants to the right hand side
     * of the expression.
     * @param statement the select statement
     * @param resolver 
     * @return new select statement
     * @throws SQLException 
     */
    public static SelectStatement normalize(SelectStatement statement, ColumnResolver resolver) throws SQLException {
        return rewrite(statement, new StatementNormalizer(resolver, statement.getSelect().size(), statement.getFrom().size() > 1));
    }
    
    @Override
    public ParseNode visitLeave(ComparisonParseNode node, List<ParseNode> nodes) throws SQLException {
         if (nodes.get(0).isStateless() && !nodes.get(1).isStateless()) {
             List<ParseNode> normNodes = Lists.newArrayListWithExpectedSize(2);
             normNodes.add(nodes.get(1));
             normNodes.add(nodes.get(0));
             nodes = normNodes;
             node = NODE_FACTORY.comparison(node.getInvertFilterOp(), nodes.get(0), nodes.get(1));
         }
         return super.visitLeave(node, nodes);
    }
    
    @Override
    public ParseNode visitLeave(final BetweenParseNode node, List<ParseNode> nodes) throws SQLException {
       
        LessThanOrEqualParseNode lhsNode =  NODE_FACTORY.lte(node.getChildren().get(1), node.getChildren().get(0));
        LessThanOrEqualParseNode rhsNode =  NODE_FACTORY.lte(node.getChildren().get(0), node.getChildren().get(2));
        List<ParseNode> parseNodes = Lists.newArrayListWithExpectedSize(2);
        parseNodes.add(this.visitLeave(lhsNode, lhsNode.getChildren()));
        parseNodes.add(this.visitLeave(rhsNode, rhsNode.getChildren()));
        return super.visitLeave(node, parseNodes);
    }

    @Override
    public ParseNode visit(ColumnParseNode node) throws SQLException {
        if (useFullNameForAlias 
                && node.getAlias() != null 
                && node.getTableName() != null
                && SchemaUtil.normalizeIdentifier(node.getAlias()).equals(node.getName())) {
            node = NODE_FACTORY.column(NODE_FACTORY.table(node.getSchemaName(), node.getTableName()), 
                    node.isCaseSensitive() ? '"' + node.getName() + '"' : node.getName(), 
                    node.isCaseSensitive() ? '"' + node.getFullName() + '"' : node.getFullName());
        }
        return super.visit(node);
    }
}
