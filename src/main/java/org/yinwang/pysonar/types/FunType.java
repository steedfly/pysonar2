package org.yinwang.pysonar.types;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yinwang.pysonar.Analyzer;
import org.yinwang.pysonar.Language;
import org.yinwang.pysonar.State;
import org.yinwang.pysonar.TypeStack;
import org.yinwang.pysonar.ast.Function;

import java.util.*;


public class FunType extends Type {

    @NotNull
    public Map<Type, Type> arrows = new HashMap<>();
    public Function func;
    @Nullable
    public ClassType cls = null;
    private State env;
    @Nullable
    public Type selfType;                 // self's type for calls
    public List<Type> defaultTypes;       // types for default parameters (evaluated at def time)


    public FunType() {
    }


    public FunType(Function func, State env) {
        this.func = func;
        this.env = env;
    }


    public FunType(Type from, Type to) {
        addMapping(from, to);
        getTable().addSuper(Analyzer.self.builtins.BaseFunction.getTable());
        getTable().setPath(Analyzer.self.builtins.BaseFunction.getTable().getPath());
    }


    public void addMapping(Type from, Type to) {
        if (arrows.size() < 5) {
            arrows.put(from, to);
            Map<Type, Type> oldArrows = arrows;
            arrows = compressArrows(arrows);

            if (toString().length() > 900) {
                arrows = oldArrows;
            }
        }
    }


    @Nullable
    public Type getMapping(@NotNull Type from) {
        return arrows.get(from);
    }


    public Type getReturnType() {
        if (!arrows.isEmpty()) {
            return arrows.values().iterator().next();
        } else {
            return Analyzer.self.builtins.unknown;
        }
    }


    public Function getFunc() {
        return func;
    }


    public State getEnv() {
        return env;
    }


    @Nullable
    public ClassType getCls() {
        return cls;
    }


    public void setCls(ClassType cls) {
        this.cls = cls;
    }


    @Nullable
    public Type getSelfType() {
        return selfType;
    }


    public void setSelfType(Type selfType) {
        this.selfType = selfType;
    }


    public void clearSelfType() {
        this.selfType = null;
    }


    public List<Type> getDefaultTypes() {
        return defaultTypes;
    }


    public void setDefaultTypes(List<Type> defaultTypes) {
        this.defaultTypes = defaultTypes;
    }


    @Override
    public boolean equals(Object other) {
        if (other instanceof FunType) {
            FunType fo = (FunType) other;
            return fo.getTable().getPath().equals(getTable().getPath()) || this == other;
        } else {
            return false;
        }
    }


    static Type removeNoneReturn(@NotNull Type toType) {
        if (toType.isUnionType()) {
            Set<Type> types = new HashSet<>(toType.asUnionType().getTypes());
            types.remove(Analyzer.self.builtins.Cont);
            return UnionType.newUnion(types);
        } else {
            return toType;
        }
    }


    @Override
    public int hashCode() {
        return "FunType".hashCode();
    }


    private boolean subsumed(Type type1, Type type2) {
        return subsumedInner(type1, type2, new TypeStack());
    }


    private boolean subsumedInner(Type type1, Type type2, TypeStack typeStack) {
        if (typeStack.contains(type1, type2)) {
            return true;
        }

        if (type1.isUnknownType() || type1 == Analyzer.self.builtins.None || type1.equals(type2)) {
            return true;
        }

        if (type1 instanceof TupleType && type2 instanceof TupleType) {
            List<Type> elems1 = ((TupleType) type1).getElementTypes();
            List<Type> elems2 = ((TupleType) type2).getElementTypes();

            if (elems1.size() == elems2.size()) {
                typeStack.push(type1, type2);
                for (int i = 0; i < elems1.size(); i++) {
                    if (!subsumedInner(elems1.get(i), elems2.get(i), typeStack)) {
                        typeStack.pop(type1, type2);
                        return false;
                    }
                }
            }

            return true;
        }

        if (type1 instanceof ListType && type2 instanceof ListType) {
            return subsumedInner(((ListType) type1).toTupleType(), ((ListType) type2).toTupleType(), typeStack);
        }

        return false;
    }


    private Map<Type, Type> compressArrows(Map<Type, Type> arrows) {
        Map<Type, Type> ret = new HashMap<>();

        for (Map.Entry<Type, Type> e1 : arrows.entrySet()) {
            boolean subsumed = false;

            for (Map.Entry<Type, Type> e2 : arrows.entrySet()) {
                if (e1 != e2 && subsumed(e1.getKey(), e2.getKey())) {
                    subsumed = true;
                    break;
                }
            }

            if (!subsumed) {
                ret.put(e1.getKey(), e1.getValue());
            }
        }

        return ret;
    }


    // If the self type is set, use the self type in the display
    // This is for display purpose only, it may not be logically
    //   correct wrt some pathological programs
    private TupleType simplifySelf(TupleType from) {
        TupleType simplified = new TupleType();
        if (from.getElementTypes().size() > 0) {
            if (cls != null) {
                simplified.add(cls.getCanon());
            } else {
                simplified.add(from.get(0));
            }
        }

        for (int i = 1; i < from.getElementTypes().size(); i++) {
            simplified.add(from.get(i));
        }
        return simplified;
    }


    @Override
    protected String printType(@NotNull CyclicTypeRecorder ctr) {

        if (arrows.isEmpty()) {
            return "? -> ?";
        }

        StringBuilder sb = new StringBuilder();

        Integer num = ctr.visit(this);
        if (num != null) {
            sb.append("#").append(num);
        } else {
            int newNum = ctr.push(this);

            int i = 0;
            Set<String> seen = new HashSet<>();

            for (Map.Entry<Type, Type> e : arrows.entrySet()) {
                Type from = e.getKey();
                if (Analyzer.self.language == Language.PYTHON && from.isTupleType()) {
                    from = simplifySelf(from.asTupleType());
                }

                String as = from.printType(ctr) + " -> " + e.getValue().printType(ctr);

                if (!seen.contains(as)) {
                    if (i != 0) {
                        if (Analyzer.self.multilineFunType) {
                            sb.append("\n| ");
                        } else {
                            sb.append(" | ");
                        }
                    }

                    sb.append(as);
                    seen.add(as);
                }

                i++;
            }

            if (ctr.isUsed(this)) {
                sb.append("=#").append(newNum).append(": ");
            }
            ctr.pop(this);
        }
        return sb.toString();
    }
}
