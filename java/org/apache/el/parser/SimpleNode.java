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
/* Generated By:JJTree: Do not edit this line. SimpleNode.java */
package org.apache.el.parser;

import java.util.Arrays;

import javax.el.ELException;
import javax.el.MethodInfo;
import javax.el.PropertyNotWritableException;
import javax.el.ValueReference;

import org.apache.el.lang.ELSupport;
import org.apache.el.lang.EvaluationContext;
import org.apache.el.util.MessageFactory;

/**
 * @author Jacob Hookom [jacob@hookom.net]
 */
public abstract class SimpleNode extends ELSupport implements Node {

    /*
     * Uses SimpleNode rather than Node for performance.
     *
     * See https://bz.apache.org/bugzilla/show_bug.cgi?id=68068
     */
    protected SimpleNode parent;

    protected SimpleNode[] children;

    protected final int id;

    protected String image;


    public SimpleNode(int i) {
        id = i;
    }


    @Override
    public void jjtOpen() {
        // NOOP by default
    }


    @Override
    public void jjtClose() {
        // NOOP by default
    }


    @Override
    public void jjtSetParent(Node n) {
        parent = (SimpleNode) n;
    }


    @Override
    public Node jjtGetParent() {
        return parent;
    }


    @Override
    public void jjtAddChild(Node n, int i) {
        if (children == null) {
            children = new SimpleNode[i + 1];
        } else if (i >= children.length) {
            SimpleNode[] c = new SimpleNode[i + 1];
            System.arraycopy(children, 0, c, 0, children.length);
            children = c;
        }
        children[i] = (SimpleNode) n;
    }


    @Override
    public Node jjtGetChild(int i) {
        return children[i];
    }


    @Override
    public int jjtGetNumChildren() {
        return (children == null) ? 0 : children.length;
    }

    /*
     * You can override these two methods in subclasses of SimpleNode to customize the way the node appears when the
     * tree is dumped. If your output uses more than one line you should override toString(String), otherwise overriding
     * toString() is probably all you need to do.
     */


    @Override
    public String toString() {
        if (this.image != null) {
            return ELParserTreeConstants.jjtNodeName[id] + "[" + this.image + "]";
        }
        return ELParserTreeConstants.jjtNodeName[id];
    }


    @Override
    public String getImage() {
        return image;
    }


    public void setImage(String image) {
        this.image = image;
    }


    @Override
    public Class<?> getType(EvaluationContext ctx) throws ELException {
        throw new UnsupportedOperationException();
    }


    @Override
    public Object getValue(EvaluationContext ctx) throws ELException {
        throw new UnsupportedOperationException();
    }


    @Override
    public boolean isReadOnly(EvaluationContext ctx) throws ELException {
        return true;
    }


    @Override
    public void setValue(EvaluationContext ctx, Object value) throws ELException {
        throw new PropertyNotWritableException(MessageFactory.get("error.syntax.set"));
    }


    @Override
    public void accept(NodeVisitor visitor) throws Exception {
        visitor.visit(this);
        if (this.children != null) {
            for (Node child : this.children) {
                child.accept(visitor);
            }
        }
    }


    @Override
    public Object invoke(EvaluationContext ctx, Class<?>[] paramTypes, Object[] paramValues) throws ELException {
        throw new UnsupportedOperationException();
    }


    @Override
    public MethodInfo getMethodInfo(EvaluationContext ctx, Class<?>[] paramTypes) throws ELException {
        throw new UnsupportedOperationException();
    }


    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + Arrays.hashCode(children);
        result = prime * result + id;
        result = prime * result + ((image == null) ? 0 : image.hashCode());
        return result;
    }


    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SimpleNode)) {
            return false;
        }
        SimpleNode other = (SimpleNode) obj;
        if (id != other.id) {
            return false;
        }
        if (image == null) {
            if (other.image != null) {
                return false;
            }
        } else if (!image.equals(other.image)) {
            return false;
        }
        return Arrays.equals(children, other.children);
    }


    /**
     * @since EL 2.2
     */
    @Override
    public ValueReference getValueReference(EvaluationContext ctx) {
        return null;
    }


    /**
     * @since EL 2.2
     */
    @Override
    public boolean isParametersProvided() {
        return false;
    }
}
