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
package org.apache.el.parser;

import javax.el.ELProcessor;

import org.junit.Assert;
import org.junit.Test;

public class TestAstAnd {

    @Test
    public void test01() {
        ELProcessor processor = new ELProcessor();
        Object result = processor.eval("true && true");
        Assert.assertEquals(Boolean.TRUE, result);
    }

    @Test
    public void test02() {
        ELProcessor processor = new ELProcessor();
        Object result = processor.eval("true && null");
        Assert.assertEquals(Boolean.FALSE, result);
    }

    @Test
    public void test03() {
        ELProcessor processor = new ELProcessor();
        Object result = processor.eval("null && true");
        Assert.assertEquals(Boolean.FALSE, result);
    }

    @Test
    public void test04() {
        ELProcessor processor = new ELProcessor();
        Object result = processor.eval("null && null");
        Assert.assertEquals(Boolean.FALSE, result);
    }

    @Test
    public void test05() {
        ELProcessor processor = new ELProcessor();
        Object result = processor.eval("true && true && true && true && true");
        Assert.assertEquals(Boolean.TRUE, result);
    }

    @Test
    public void test06() {
        ELProcessor processor = new ELProcessor();
        Object result = processor.eval("true && true && true && true && false");
        Assert.assertEquals(Boolean.FALSE, result);
    }

    @Test
    public void test07() {
        ELProcessor processor = new ELProcessor();
        Object result = processor.eval("false && true && true && true && true");
        Assert.assertEquals(Boolean.FALSE, result);
    }

    @Test
    public void test08() {
        ELProcessor processor = new ELProcessor();
        Object result = processor.eval("true && false && true && true && true");
        Assert.assertEquals(Boolean.FALSE, result);
    }

    @Test
    public void test09() {
        ELProcessor processor = new ELProcessor();
        Object result = processor.eval("true && true && false && true && true");
        Assert.assertEquals(Boolean.FALSE, result);
    }

    @Test
    public void test10() {
        ELProcessor processor = new ELProcessor();
        Object result = processor.eval("true && true && true && false &&  true");
        Assert.assertEquals(Boolean.FALSE, result);
    }

}
