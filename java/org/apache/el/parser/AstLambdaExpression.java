/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/* Generated By:JJTree: Do not edit this line. AstLambdaExpression.java Version 4.3 */
package org.apache.el.parser;

import java.util.ArrayList;
import java.util.List;

import javax.el.ELException;
import javax.el.LambdaExpression;

import org.apache.el.ValueExpressionImpl;
import org.apache.el.lang.EvaluationContext;
import org.apache.el.lang.LambdaExpressionNestedState;
import org.apache.el.util.MessageFactory;

public class AstLambdaExpression extends SimpleNode {

    public AstLambdaExpression(int id) {
        super(id);
    }


    @Override
    public Object getValue(EvaluationContext ctx) throws ELException {

        // Correct evaluation requires knowledge of the whole set of nested
        // expressions, not just the current expression
        LambdaExpressionNestedState state = ctx.getLambdaExpressionNestedState();
        if (state == null) {
            // This must be an outer lambda expression. Create and populate the
            // state.
            state = new LambdaExpressionNestedState();
            populateNestedState(state);
            ctx.setLambdaExpressionNestedState(state);
        }

        // Check that there are not more sets of parameters than there are
        // nested expressions.
        int methodParameterSetCount = jjtGetNumChildren() - 2;
        if (methodParameterSetCount > state.getNestingCount()) {
            throw new ELException(MessageFactory.get("error.lambda.tooManyMethodParameterSets"));
        }

        // First child is always parameters even if there aren't any
        AstLambdaParameters formalParametersNode = (AstLambdaParameters) children[0];
        Node[] formalParamNodes = formalParametersNode.children;

        // Second child is a value expression
        ValueExpressionImpl ve =
                new ValueExpressionImpl("", children[1], ctx.getFunctionMapper(), ctx.getVariableMapper(), null);

        // Build a LambdaExpression
        List<String> formalParameters = new ArrayList<>();
        if (formalParamNodes != null) {
            for (Node formalParamNode : formalParamNodes) {
                formalParameters.add(formalParamNode.getImage());
            }
        }
        LambdaExpression le = new LambdaExpression(formalParameters, ve);
        le.setELContext(ctx);

        if (jjtGetNumChildren() == 2) {
            // No method parameters
            // Can only invoke the expression if none of the lambda expressions
            // in the nesting declare parameters
            if (state.getHasFormalParameters()) {
                return le;
            } else {
                return le.invoke(ctx, (Object[]) null);
            }
        }

        /*
         * This is a (possibly nested) lambda expression with one or more sets of parameters provided.
         *
         * If there are more nested expressions than sets of parameters this may return a LambdaExpression.
         *
         * If there are more sets of parameters than nested expressions an ELException will have been thrown by the
         * check at the start of this method.
         */

        // Always have to invoke the outer-most expression
        int methodParameterIndex = 2;
        Object result = le.invoke(((AstMethodParameters) children[methodParameterIndex]).getParameters(ctx));
        methodParameterIndex++;

        while (result instanceof LambdaExpression && methodParameterIndex < jjtGetNumChildren()) {
            result = ((LambdaExpression) result)
                    .invoke(((AstMethodParameters) children[methodParameterIndex]).getParameters(ctx));
            methodParameterIndex++;
        }

        return result;
    }


    private void populateNestedState(LambdaExpressionNestedState lambdaExpressionNestedState) {
        // Increment the nesting count for the current expression
        lambdaExpressionNestedState.incrementNestingCount();

        if (jjtGetNumChildren() > 1) {
            Node firstChild = jjtGetChild(0);
            if (firstChild instanceof AstLambdaParameters) {
                if (firstChild.jjtGetNumChildren() > 0) {
                    lambdaExpressionNestedState.setHasFormalParameters();
                }
            } else {
                // Can't be a lambda expression
                return;
            }
            Node secondChild = jjtGetChild(1);
            if (secondChild instanceof AstLambdaExpression) {
                ((AstLambdaExpression) secondChild).populateNestedState(lambdaExpressionNestedState);
            }
        }
    }


    @Override
    public String toString() {
        // Purely for debug purposes. May not be complete or correct. Certainly
        // is not efficient. Be sure not to call this from 'real' code.
        StringBuilder result = new StringBuilder();
        for (Node n : children) {
            result.append(n.toString());
        }
        return result.toString();
    }
}
/* JavaCC - OriginalChecksum=071159eff10c8e15ec612c765ae4480a (do not edit this line) */
