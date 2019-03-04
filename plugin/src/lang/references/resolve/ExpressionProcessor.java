package com.siberika.idea.pascal.lang.references.resolve;

import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.search.PsiElementProcessor;
import com.siberika.idea.pascal.lang.parser.NamespaceRec;
import com.siberika.idea.pascal.lang.psi.PasArgumentList;
import com.siberika.idea.pascal.lang.psi.PasCallExpr;
import com.siberika.idea.pascal.lang.psi.PasClassProperty;
import com.siberika.idea.pascal.lang.psi.PasDereferenceExpr;
import com.siberika.idea.pascal.lang.psi.PasEntityScope;
import com.siberika.idea.pascal.lang.psi.PasExpr;
import com.siberika.idea.pascal.lang.psi.PasFullyQualifiedIdent;
import com.siberika.idea.pascal.lang.psi.PasIndexExpr;
import com.siberika.idea.pascal.lang.psi.PasProductExpr;
import com.siberika.idea.pascal.lang.psi.PasReferenceExpr;
import com.siberika.idea.pascal.lang.psi.PasTypeID;
import com.siberika.idea.pascal.lang.psi.PascalNamedElement;
import com.siberika.idea.pascal.lang.psi.PascalRoutine;
import com.siberika.idea.pascal.lang.psi.impl.PasField;
import com.siberika.idea.pascal.lang.psi.impl.PascalExpression;
import com.siberika.idea.pascal.lang.references.PasReferenceUtil;
import com.siberika.idea.pascal.lang.references.ResolveContext;
import com.siberika.idea.pascal.util.PsiUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

class ExpressionProcessor implements PsiElementProcessor<PasReferenceExpr> {

    private final NamespaceRec fqn;
    private final ResolveContext context;
    private final ResolveProcessor processor;
    private PasEntityScope currentScope;

    ExpressionProcessor(final NamespaceRec fqn, final ResolveContext context, final ResolveProcessor processor) {
        this.fqn = fqn;
        this.context = context;
        this.processor = processor;
        this.currentScope = null;
    }

    @Override
    public boolean execute(@NotNull final PasReferenceExpr refExpr) {
        if (refExpr.getFullyQualifiedIdent() != fqn.getParentIdent()) {              // Not the FQN which originally requested
            final FQNResolver fqnResolver = new FQNResolver(currentScope, NamespaceRec.fromElement(refExpr.getFullyQualifiedIdent()), context) {
                @Override
                boolean processField(final PasEntityScope scope, final PasField field) {
                    currentScope = retrieveScope(scope, field);
                    return false;
                }
            };
            return fqnResolver.resolve(refExpr.getExpr() == null);
        } else {
            final FQNResolver fqnResolver = new FQNResolver(currentScope, fqn, context) {
                @Override
                boolean processScope(final PasEntityScope scope, final String fieldName) {
                    if (fieldName == null) {
                        return true;
                    }
                    boolean isDefault = "DEFAULT".equals(fieldName.toUpperCase());
                    if ((fqn.isTarget() || isDefault) && isWasType()) {         // "default" type pseudo value
                        PasField field = scope.getField(fieldName);
                        if (field != null) {
                            return processField(scope, field);
                        }
                            fqn.next();
                            if (isDefault) {
                                PasField defaultField = new PasField(scope, scope, "default", PasField.FieldType.CONSTANT, PasField.Visibility.PUBLIC);
                                return processField(scope, defaultField);
                            }
                    } else {
                        return processDefault(scope, fieldName);
                    }
                    return true;
                }

                @Override
                boolean processField(final PasEntityScope scope, final PasField field) {
                    return processor.process(scope, scope, field, field.fieldType);
                }
            };
            return fqnResolver.resolve(refExpr.getExpr() == null);
        }
    }

    boolean resolveExprTypeScope(PascalExpression expression, boolean lastPart) {
        if (expression instanceof PasReferenceExpr) {
            PasExpr scopeExpr = ((PasReferenceExpr) expression).getExpr();
            // Resolve FQN in scope of Expr
            if (scopeExpr != null) {
                resolveExprTypeScope((PascalExpression) scopeExpr, false);
            }
            return execute((PasReferenceExpr) expression);
        } else if (expression instanceof PasDereferenceExpr) {
            return resolveExprTypeScope((PascalExpression) ((PasDereferenceExpr) expression).getExpr(), false);
        } else if (expression instanceof PasIndexExpr) {
            return handleArray((PasIndexExpr) expression, lastPart);
        } else if (expression instanceof PasProductExpr) {                                      // AS operator case
            Operation op = Operation.forId(((PasProductExpr) expression).getMulOp().getText());
            if (op == Operation.AS) {
                List<PasExpr> exprs = ((PasProductExpr) expression).getExprList();
                return exprs.size() < 2 || resolveExprTypeScope((PascalExpression) exprs.get(1), false);
            }
        } else if (expression instanceof PasCallExpr) {
            return handleCall((PasCallExpr) expression, lastPart);
        }
        PsiElement child = getFirstChild(expression);
        if (child instanceof PascalExpression) {
            return resolveExprTypeScope((PascalExpression) child, false);
        }
        return true;
    }

    private boolean handleArray(final PasIndexExpr indexExpr, final boolean lastPart) {
        final boolean result = resolveExprTypeScope((PascalExpression) indexExpr.getExpr(), lastPart);
        PascalNamedElement defProp = currentScope != null ? PsiUtil.getDefaultProperty(currentScope) : null;    // Replace scope if indexing default array property
        if (defProp instanceof PasClassProperty) {
            PasTypeID typeId = ((PasClassProperty) defProp).getTypeID();
            final PasField field = typeId != null ? resolveType(currentScope, typeId.getFullyQualifiedIdent()) : null;
            if (field != null) {
                currentScope = retrieveScope(currentScope, field);
            } else {
                currentScope = null;
            }
        }
        return result;
    }

    private static PasField resolveType(PasEntityScope scope, PasFullyQualifiedIdent fullyQualifiedIdent) {
        ResolveContext context = new ResolveContext(scope, PasField.TYPES_ALL, true, null, null);
        final Collection<PasField> references = PasReferenceUtil.resolve(NamespaceRec.fromElement(fullyQualifiedIdent), context, 0);
        if (!references.isEmpty()) {
            PasField field = references.iterator().next();
            if (!field.isConstructor()) {        // TODO: move constructor handling to main resolve routine
                PasReferenceUtil.retrieveFieldTypeScope(field, new ResolveContext(field.owner, PasField.TYPES_TYPE, true, null, context.unitNamespaces));
            }
            return field;
        }
        return null;
    }

    private PasField callTarget;
    private PasEntityScope callTargetScope;

    private boolean handleCall(final PasCallExpr callExpr, final boolean lastPart) {
        final PasExpr expr = callExpr.getExpr();
        if (expr instanceof PasReferenceExpr) {             // call of a routine specified explicitly with its name
            callTarget = null;
            PasExpr scopeExpr = ((PasReferenceExpr) expr).getExpr();
            if (scopeExpr != null) {
                resolveExprTypeScope((PascalExpression) scopeExpr, false);
            }
            // Resolve FQN in current scope
            PasFullyQualifiedIdent fullyQualifiedIdent = ((PasReferenceExpr) expr).getFullyQualifiedIdent();
            final FQNResolver fqnResolver = new FQNResolver(currentScope, NamespaceRec.fromElement(fullyQualifiedIdent), context) {
                @Override
                boolean processScope(final PasEntityScope scope, final String fieldName) {
                    final PasArgumentList args = callExpr.getArgumentList();
                    final int argsCount = args.getExprList().size();
                    for (PasField field : scope.getAllFields()) {
                        if ((field.fieldType == PasField.FieldType.ROUTINE) && fullyQualifiedIdent.getNamePart().equalsIgnoreCase(field.name)) {
                            PascalNamedElement el = field.getElement();
                            if (el instanceof PascalRoutine) {
                                PascalRoutine routine = (PascalRoutine) el;
                                if (routine.getFormalParameterNames().size() == argsCount) {
                                    if (lastPart) {                  // Return resolved field
                                        return ExpressionProcessor.this.processor.process(scope, scope, field, field.fieldType);
                                    } else {                         // Resolve next scope
                                        currentScope = retrieveScope(scope, field);
                                        return false;
                                    }
                                }
                            }
                        }
                    }
                    // Get call target field to use if exact call target will be not found
                    if (null == callTarget) {
                        callTarget = scope.getField(fieldName);
                        callTargetScope = scope;
                    }
                    return true;
                }

                @Override
                boolean processField(final PasEntityScope scope, final PasField field) {
                    return true;
                }
            };
            if (fqnResolver.resolve(scopeExpr == null)) {               // No call candidates found
                if (callTarget != null) {
                    if (lastPart) {                  // Return resolved field
                        return ExpressionProcessor.this.processor.process(currentScope, callTargetScope, callTarget, callTarget.fieldType);
                    } else {                         // Resolve next scope
                        currentScope = retrieveScope(callTargetScope, callTarget);
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static PsiElement getFirstChild(PascalExpression expr) {
        PsiElement res = expr.getFirstChild();
        while ((res != null) && (res.getClass() == LeafPsiElement.class)) {
            res = res.getNextSibling();
        }
        return res;
    }

    // TODO: replace with PasNamedIdent.getScope() or getType()
    private PasEntityScope retrieveScope(PasEntityScope scope, PasField field) {
        if (field.fieldType == PasField.FieldType.UNIT) {
            return (PasEntityScope) field.getElement();
        }
        return PasReferenceUtil.retrieveFieldTypeScope(field, context);
    }

}
