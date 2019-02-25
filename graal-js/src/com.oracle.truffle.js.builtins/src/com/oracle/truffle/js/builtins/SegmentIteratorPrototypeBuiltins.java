/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.builtins;

import com.ibm.icu.text.BreakIterator;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.js.builtins.SegmentIteratorPrototypeBuiltinsFactory.SegmentIteratorNextNodeGen;
import com.oracle.truffle.js.builtins.SegmentIteratorPrototypeBuiltinsFactory.SegmentIteratorFollowingNodeGen;
import com.oracle.truffle.js.builtins.SegmentIteratorPrototypeBuiltinsFactory.SegmentIteratorPrecedingNodeGen;
import com.oracle.truffle.js.nodes.access.CreateIterResultObjectNode;
import com.oracle.truffle.js.nodes.access.HasHiddenKeyCacheNode;
import com.oracle.truffle.js.nodes.access.PropertyGetNode;
import com.oracle.truffle.js.nodes.access.PropertySetNode;
import com.oracle.truffle.js.nodes.cast.JSToIndexNode;
import com.oracle.truffle.js.nodes.function.JSBuiltin;
import com.oracle.truffle.js.nodes.function.JSBuiltinNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.builtins.BuiltinEnum;
import com.oracle.truffle.js.runtime.builtins.JSSegmenter;
import com.oracle.truffle.js.runtime.builtins.JSUserObject;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Undefined;

/**
 * Contains functions of the %SegmentIteratorPrototype% object.
 */
public final class SegmentIteratorPrototypeBuiltins extends JSBuiltinsContainer.SwitchEnum<SegmentIteratorPrototypeBuiltins.SegmentIteratorPrototype> {
    protected SegmentIteratorPrototypeBuiltins() {
        super(JSSegmenter.ITERATOR_PROTOTYPE_NAME, SegmentIteratorPrototype.class);
    }

    public enum SegmentIteratorPrototype implements BuiltinEnum<SegmentIteratorPrototype> {
        next(0),
        preceding(1),
        following(1);

        private final int length;

        SegmentIteratorPrototype(int length) {
            this.length = length;
        }

        @Override
        public int getLength() {
            return length;
        }
    }

    @Override
    protected Object createNode(JSContext context, JSBuiltin builtin, boolean construct, boolean newTarget, SegmentIteratorPrototype builtinEnum) {
        switch (builtinEnum) {
            case next:
                return SegmentIteratorNextNodeGen.create(context, builtin, args().withThis().createArgumentNodes(context));
            case preceding:
                return SegmentIteratorPrecedingNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
            case following:
                return SegmentIteratorFollowingNodeGen.create(context, builtin, args().withThis().fixedArgs(1).createArgumentNodes(context));
        }
        return null;
    }

    public abstract static class SegmentIteratorOpNode extends JSBuiltinNode {

        @Child protected HasHiddenKeyCacheNode isSegmentIteratorNode;
        @Child protected PropertyGetNode getSegmentIteratorKindNode;
        @Child protected PropertyGetNode getIteratedObjectNode;
        @Child protected PropertyGetNode getSegmenterNode;
        @Child protected PropertySetNode setBreakTypeNode;
        @Child protected PropertySetNode setIndexNode;

        public SegmentIteratorOpNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
            this.isSegmentIteratorNode = HasHiddenKeyCacheNode.create(JSSegmenter.SEGMENT_ITERATOR_KIND_ID);
            this.getSegmentIteratorKindNode = PropertyGetNode.createGetHidden(JSSegmenter.SEGMENT_ITERATOR_KIND_ID, context);
            this.getIteratedObjectNode = PropertyGetNode.createGetHidden(JSRuntime.ITERATED_OBJECT_ID, context);
            this.getSegmenterNode = PropertyGetNode.createGetHidden(JSSegmenter.SEGMENT_ITERATOR_SEGMENTER_ID, context);
            this.setIndexNode = PropertySetNode.createSetHidden(JSSegmenter.SEGMENT_ITERATOR_INDEX_ID,
                            context);
            this.setBreakTypeNode = PropertySetNode.createSetHidden(JSSegmenter.SEGMENT_ITERATOR_BREAK_TYPE_ID, context);
        }

        protected final boolean isSegmentIterator(Object thisObj) {
            return isSegmentIteratorNode.executeHasHiddenKey(thisObj);
        }
    }

    public abstract static class SegmentIteratorNextNode extends SegmentIteratorOpNode {

        @Child protected CreateIterResultObjectNode createIterResultObjectNode;

        public SegmentIteratorNextNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
            this.createIterResultObjectNode = CreateIterResultObjectNode.create(context);
        }

        @Specialization(guards = "isSegmentIterator(iterator)")
        protected DynamicObject doSegmentIterator(VirtualFrame frame, DynamicObject iterator) {

            Object iteratedString = getIteratedObjectNode.getValue(iterator);
            if (iteratedString == Undefined.instance) {
                return createIterResultObjectNode.execute(frame, null, true);
            }
            BreakIterator icuIterator = (BreakIterator) getSegmenterNode.getValue(iterator);
            JSSegmenter.Kind segmenterKind = (JSSegmenter.Kind) getSegmentIteratorKindNode.getValue(iterator);
            DynamicObject nextValue = nextValue(iterator, (String) iteratedString, segmenterKind, icuIterator);
            return createIterResultObjectNode.execute(frame, nextValue, nextValue == null);
        }

        @SuppressWarnings("unused")
        @Fallback
        protected DynamicObject doIncompatibleReceiver(Object iterator) {
            throw Errors.createTypeError("not a Segment Iterator");
        }

        @TruffleBoundary
        protected DynamicObject nextValue(DynamicObject iterator, String s, JSSegmenter.Kind segmenterKind, BreakIterator icuIterator) {

            int startIndex = icuIterator.current();
            int endIndex = icuIterator.next();
            boolean done = endIndex == BreakIterator.DONE;

            if (done) {
                setBreakTypeNode.setValue(iterator, null);
                return null;
            }

            String segment = s.substring(startIndex, endIndex);
            String breakType = segmenterKind.getBreakType(segment, icuIterator.getRuleStatus());

            DynamicObject result = makeIterationResultValue(endIndex, segment, breakType);

            setBreakTypeNode.setValue(iterator, breakType);
            setIndexNode.setValue(iterator, endIndex);

            return result;
        }

        protected DynamicObject makeIterationResultValue(int endIndex, String segment, String breakType) {
            DynamicObject result = JSUserObject.create(getContext());
            JSObject.set(result, "segment", segment);
            if (breakType != null) {
                JSObject.set(result, "breakType", breakType);
            }
            JSObject.set(result, "index", endIndex);
            return result;
        }
    }

    @ImportStatic(JSSegmenter.class)
    public abstract static class SegmentIteratorAdvanceOpNode extends SegmentIteratorOpNode {

        public SegmentIteratorAdvanceOpNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @SuppressWarnings("unused")
        int doAdvanceOp(BreakIterator icuIterator, int offset) {
            throw Errors.shouldNotReachHere();
        }

        @SuppressWarnings("unused")
        void doCheckOffsetRange(int offset, int length) {
            throw Errors.shouldNotReachHere();
        }

        private boolean checkRangeAndAdvanceOp(DynamicObject iterator, int offset) {
            Object iteratedString = getIteratedObjectNode.getValue(iterator);
            if (iteratedString instanceof String) {
                doCheckOffsetRange(offset, ((String) iteratedString).length());
            }
            return advanceOp(iterator, offset);
        }

        private boolean advanceOp(DynamicObject iterator, int offset) {
            BreakIterator icuIterator = (BreakIterator) getSegmenterNode.getValue(iterator);
            JSSegmenter.Kind segmenterKind = (JSSegmenter.Kind) getSegmentIteratorKindNode.getValue(iterator);
            int newIndex = doAdvanceOp(icuIterator, offset);
            String breakType = segmenterKind.getBreakType(null, icuIterator.getRuleStatus());
            setBreakTypeNode.setValue(iterator, breakType);
            setIndexNode.setValue(iterator, newIndex);
            return newIndex == BreakIterator.DONE;
        }

        @Specialization(guards = {"isSegmentIterator(iterator)", "!isUndefined(from)"})
        protected boolean doSegmentIteratorWithFrom(DynamicObject iterator, Object from, @Cached("create()") JSToIndexNode toIndexNode) {
            return checkRangeAndAdvanceOp(iterator, (int) toIndexNode.executeLong(from));
        }

        @Specialization(guards = {"isSegmentIterator(iterator)", "isUndefined(from)"})
        protected boolean doSegmentIteratorNoFrom(DynamicObject iterator, @SuppressWarnings("unused") Object from,
                        @Cached("createGetHidden(SEGMENT_ITERATOR_INDEX_ID, getContext())") PropertyGetNode getIndexNode) {
            try {
                return advanceOp(iterator, getIndexNode.getValueInt(iterator));
            } catch (UnexpectedResultException e) {
                throw Errors.shouldNotReachHere();
            }
        }

        @SuppressWarnings("unused")
        @Specialization(guards = "!isSegmentIterator(iterator)")
        protected DynamicObject doIncompatibleReceiver(Object iterator, Object from) {
            throw Errors.createTypeError("not a Segment Iterator");
        }
    }

    public abstract static class SegmentIteratorPrecedingNode extends SegmentIteratorAdvanceOpNode {

        public SegmentIteratorPrecedingNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Override
        final void doCheckOffsetRange(int offset, int length) {
            if (offset == 0 || offset > length) {
                throw Errors.createRangeErrorFormat("Offset out of bounds in Intl.Segment iterator %s method.", this, "preceding");
            }
        }

        @Override
        @TruffleBoundary
        final int doAdvanceOp(BreakIterator icuIterator, int offset) {
            return icuIterator.preceding(offset);
        }
    }

    public abstract static class SegmentIteratorFollowingNode extends SegmentIteratorAdvanceOpNode {

        public SegmentIteratorFollowingNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Override
        final void doCheckOffsetRange(int offset, int length) {
            if (offset >= length) {
                throw Errors.createRangeErrorFormat("Offset out of bounds in Intl.Segment iterator %s method.", this, "following");
            }
        }

        @Override
        @TruffleBoundary
        final int doAdvanceOp(BreakIterator icuIterator, int offset) {
            return icuIterator.following(offset);
        }
    }
}
