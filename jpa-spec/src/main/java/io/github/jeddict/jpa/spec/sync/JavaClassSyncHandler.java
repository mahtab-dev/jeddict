/**
 * Copyright 2013-2018 the original author or authors from the Jeddict project (https://jeddict.github.io/).
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.github.jeddict.jpa.spec.sync;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import static io.github.jeddict.jcode.BeanVaildationConstants.BV_ANNOTATIONS;
import static io.github.jeddict.jcode.BeanVaildationConstants.BV_CONSTRAINTS_PACKAGE;
import static io.github.jeddict.jcode.JAXBConstants.JAXB_ANNOTATIONS;
import static io.github.jeddict.jcode.JAXBConstants.JAXB_PACKAGE;
import static io.github.jeddict.jcode.JPAConstants.JPA_ANNOTATIONS;
import static io.github.jeddict.jcode.JPAConstants.PERSISTENCE_PACKAGE;
import static io.github.jeddict.jcode.JSONBConstants.JSONB_ANNOTATIONS;
import static io.github.jeddict.jcode.JSONBConstants.JSONB_PACKAGE;
import static io.github.jeddict.jcode.util.JavaIdentifiers.isFQN;
import static io.github.jeddict.jcode.util.JavaIdentifiers.unqualify;
import static io.github.jeddict.jcode.util.JavaUtil.getFieldName;
import static io.github.jeddict.jcode.util.JavaUtil.isBeanMethod;
import io.github.jeddict.jpa.spec.extend.Attribute;
import io.github.jeddict.jpa.spec.extend.ClassAnnotation;
import io.github.jeddict.jpa.spec.extend.ClassAnnotationLocationType;
import static io.github.jeddict.jpa.spec.extend.ClassAnnotationLocationType.TYPE;
import io.github.jeddict.jpa.spec.extend.Constructor;
import io.github.jeddict.jpa.spec.extend.IAttributes;
import io.github.jeddict.jpa.spec.extend.JavaClass;
import io.github.jeddict.jpa.spec.extend.ReferenceClass;
import io.github.jeddict.snippet.ClassSnippet;
import io.github.jeddict.snippet.ClassSnippetLocationType;
import static io.github.jeddict.snippet.ClassSnippetLocationType.AFTER_CLASS;
import static io.github.jeddict.snippet.ClassSnippetLocationType.AFTER_FIELD;
import static io.github.jeddict.snippet.ClassSnippetLocationType.AFTER_METHOD;
import static io.github.jeddict.snippet.ClassSnippetLocationType.BEFORE_PACKAGE;
import static io.github.jeddict.snippet.ClassSnippetLocationType.IMPORT;
import static io.github.jeddict.snippet.ClassSnippetLocationType.TYPE_JAVADOC;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

/**
 *
 * @author jGauravGupta
 */
public class JavaClassSyncHandler {

    private final JavaClass<IAttributes> javaClass;

    private JavaClassSyncHandler(JavaClass<IAttributes> javaClass) {
        this.javaClass = javaClass;
    }

    public static JavaClassSyncHandler getInstance(JavaClass<IAttributes> javaClass) {
        return new JavaClassSyncHandler(javaClass);
    }

    public void syncExistingSnippet(CompilationUnit existingSource) {
        Map<String, Attribute> attributes = javaClass.getAttributes().getAllAttributeMap();
        Map<String, Attribute> previousAttributes
                = javaClass.getAttributes()
                        .getAllAttribute()
                        .stream()
                        .filter(attr -> Objects.nonNull(attr.getPreviousName()))
                        .collect(toMap(Attribute::getPreviousName, identity()));

        Map<String, ImportDeclaration> imports = existingSource.getImports()
                .stream()
                .collect(toMap(importDec -> unqualify(importDec.getNameAsString()), identity()));

        NodeList<TypeDeclaration<?>> types = existingSource.getTypes();
        for (TypeDeclaration<?> type : types) {
            if (type.getNameAsString().equals(javaClass.getPreviousClass())
                    || type.getNameAsString().equals(javaClass.getClazz())) {
                ClassOrInterfaceDeclaration rootClass = (ClassOrInterfaceDeclaration) type;

                if (type.getParentNode().isPresent()) {
                    Node parentNode = type.getParentNode().get();

                    parentNode.getComment()
                            .ifPresent(comment -> syncHeader(comment, BEFORE_PACKAGE));

                    parentNode.getChildNodes()
                            .stream()
                            .filter(node -> node instanceof JavadocComment)
                            .map(node -> (JavadocComment) node)
                            .forEach(javadocComment -> syncJavadoc(javadocComment, TYPE_JAVADOC));// Alternative of type.getJavadocComment() **
                }

                syncTypeParameters(rootClass.getTypeParameters(), imports);
                syncExtendedTypes(rootClass.getExtendedTypes(), imports);
                syncImplementedTypes(rootClass.getImplementedTypes(), imports);
                syncAnnotations(rootClass.getAnnotations(), TYPE, imports);

                NodeList<BodyDeclaration<?>> members = rootClass.getMembers();
                for (BodyDeclaration<?> member : members) {
                    if (member instanceof MethodDeclaration) {
                        MethodDeclaration method = (MethodDeclaration) member;
                        String methodName = method.getNameAsString();
                        if (isBeanMethod(methodName)) {
                            String attributeName = getFieldName(methodName);

                            if (javaClass.getRemovedAttributes().contains(attributeName)) {
                                continue; //ignore deleted attribute
                            }

                            Attribute previousAttribute = previousAttributes.get(attributeName);
                            Attribute attribute = attributes.get(attributeName);
                            if (previousAttribute != null) { //renamed
                                AttributeSyncHandler
                                        .getInstance(previousAttribute)
                                        .loadExistingSnippet(attributeName, method, imports);
                            } else if (attribute != null) { // new or non-modified
                                AttributeSyncHandler
                                        .getInstance(attribute)
                                        .loadExistingSnippet(attributeName, method, imports);
                            } else {
                                syncMethodSnippet(method, imports);
                            }
                        } else if (method.getNameAsString().equals("toString")
                                && method.getParameters().isEmpty()) {
                            if (javaClass.getToStringMethod().getAttributes().isEmpty()) {
                                syncMethodSnippet(method, imports);
                            }
                        } else if (method.getNameAsString().equals("hashCode")
                                && method.getParameters().isEmpty()) {
                            if (javaClass.getHashCodeMethod().getAttributes().isEmpty()) {
                                syncMethodSnippet(method, imports);
                            }
                        } else if (method.getNameAsString().equals("equals")
                                && method.getParameters().size() == 1
                                && method.getParameters().get(0).getTypeAsString().equals("Object")) {
                            if (javaClass.getEqualsMethod().getAttributes().isEmpty()) {
                                syncMethodSnippet(method, imports);
                            }
                        } else {
                            syncMethodSnippet(method, imports);
                        }
                    } else if (member instanceof FieldDeclaration) {
                        FieldDeclaration field = (FieldDeclaration) member;
                        String attributeName = field.getVariable(0).getNameAsString();

                        if (javaClass.getRemovedAttributes().contains(attributeName)) {
                            continue; //ignore deleted attribute
                        }

                        Attribute previousAttribute = previousAttributes.get(attributeName);
                        Attribute attribute = attributes.get(attributeName);
                        if (previousAttribute != null) { //renamed
                            AttributeSyncHandler
                                    .getInstance(previousAttribute)
                                    .loadExistingSnippet(attributeName, field, imports);
                        } else if (attribute != null) { // new or non-modified
                            AttributeSyncHandler
                                    .getInstance(attribute)
                                    .loadExistingSnippet(attributeName, field, imports);
                        } else {
                            syncFieldSnippet((FieldDeclaration) member, imports);
                        }
                    } else if (member instanceof ClassOrInterfaceDeclaration || member instanceof EnumDeclaration) {
                        syncInnerClassOrInterfaceOrEnumSnippet(member, imports);
                    } else if (member instanceof InitializerDeclaration) {
                        syncInitializationBlockSnippet((InitializerDeclaration) member, imports);
                    } else if (member instanceof ConstructorDeclaration) {
                        syncConstructorSnippet((ConstructorDeclaration) member, imports);
                    } else {
                        System.out.println("member not supported");
                    }
                }
            } else if (type instanceof ClassOrInterfaceDeclaration || type instanceof EnumDeclaration) {
                syncClassOrInterfaceOrEnumSnippet(type, imports);
            } else {
                System.out.println("member not supported");
            }
        }
    }

    private void syncHeader(Comment comment, ClassSnippetLocationType locationType) {
        javaClass.addRuntimeSnippet(new ClassSnippet(comment.toString(), locationType));
    }

    private void syncJavadoc(JavadocComment javadocComment, ClassSnippetLocationType locationType) {
        String value = javadocComment.toString();
        if (javaClass.getDescription() == null || !value.contains(javaClass.getDescription())) {
            javaClass.addRuntimeSnippet(new ClassSnippet(value, locationType));
        }
    }

    private void syncTypeParameters(List<TypeParameter> typeParameters, Map<String, ImportDeclaration> imports) {
        for (TypeParameter typeParameter : typeParameters) {
            String value = typeParameter.toString();
            javaClass.addRuntimeTypeParameter(value);
            syncImportSnippet(value, imports);;
        }
    }

    private void syncExtendedTypes(List<ClassOrInterfaceType> extendedTypes, Map<String, ImportDeclaration> imports) {
        if (extendedTypes.size() != 1) {
            return; // single extends is valid for entity
        }
        ClassOrInterfaceType extendedType = extendedTypes.get(0);
        String value = extendedType.toString();
        if (javaClass.getSuperclassRef() == null && javaClass.getSuperclass() == null) {
            javaClass.setRuntimeSuperclassRef(new ReferenceClass(value));
            syncImportSnippet(value, imports);;
        }
    }

    private void syncImplementedTypes(List<ClassOrInterfaceType> implementedTypes, Map<String, ImportDeclaration> imports) {
        Set<ReferenceClass> allInterfaces = new LinkedHashSet<>(javaClass.getRootElement().getInterfaces());
        allInterfaces.addAll(javaClass.getInterfaces());

        for (ClassOrInterfaceType implementedType : implementedTypes) {
            String implementedExprName = implementedType.getNameAsString();
            String implementedName;
            if (isFQN(implementedExprName)) {
                implementedName = unqualify(implementedExprName);
            } else {
                implementedName = implementedExprName;
            }

            String value = implementedType.toString();
            if (!allInterfaces
                    .stream()
                    .filter(inter -> inter.isEnable())
                    .filter(inter -> inter.getName().contains(implementedName))
                    .findAny()
                    .isPresent()) {
                javaClass.addRuntimeInterface(new ReferenceClass(value));
                syncImportSnippet(value, imports);;
            }
        }
    }

    private void syncAnnotations(List<AnnotationExpr> annotationExprs, ClassAnnotationLocationType locationType, Map<String, ImportDeclaration> imports) {
        for (AnnotationExpr annotationExpr : annotationExprs) {
            String annotationExprName = annotationExpr.getNameAsString();
            String annotationName;
            String annotationFQN;
            //TODO calculate using resolve type or find solution for static import ??
            if (isFQN(annotationExprName)) {
                annotationFQN = annotationExprName;
                annotationName = unqualify(annotationExprName);
            } else {
                annotationFQN = imports.containsKey(annotationExprName)
                        ? imports.get(annotationExprName).getNameAsString() : annotationExprName;
                annotationName = annotationExprName;
            }

            if (!annotationFQN.startsWith(PERSISTENCE_PACKAGE)
                    && !annotationFQN.startsWith(BV_CONSTRAINTS_PACKAGE)
                    && !annotationFQN.startsWith(JSONB_PACKAGE)
                    && !annotationFQN.startsWith(JAXB_PACKAGE)
                    && !JPA_ANNOTATIONS.contains(annotationFQN)
                    && !BV_ANNOTATIONS.contains(annotationFQN)
                    && !JSONB_ANNOTATIONS.contains(annotationFQN)
                    && !JAXB_ANNOTATIONS.contains(annotationFQN)) {

                String value = annotationExpr.toString();
                if (!javaClass.getAnnotation()
                        .stream()
                        .filter(anot -> anot.getLocationType() == locationType)
                        .filter(anot -> anot.getName().contains(annotationName))
                        .findAny()
                        .isPresent()) {
                    javaClass.addRuntimeAnnotation(new ClassAnnotation(value, locationType));
                    syncImportSnippet(value, imports);;
                }
            }

        }
    }

    private void syncFieldSnippet(FieldDeclaration field, Map<String, ImportDeclaration> imports) {
        syncClassSnippet(AFTER_FIELD, field.toString(), imports);
    }

    private void syncInitializationBlockSnippet(InitializerDeclaration initializationBlock, Map<String, ImportDeclaration> imports) {
        syncClassSnippet(AFTER_FIELD, initializationBlock.toString(), imports);
    }

    private void syncConstructorSnippet(ConstructorDeclaration constructor, Map<String, ImportDeclaration> imports) {
        String signature
                = constructor.getParameters()
                        .stream()
                        .map(Parameter::getTypeAsString)
                        .collect(joining(", "));
        if (!javaClass.getConstructors()
                .stream()
                .filter(Constructor::isEnable)
                .filter(cot -> cot.getSignature().equals(signature))
                .findAny()
                .isPresent()) {
            syncClassSnippet(AFTER_FIELD, constructor.toString(), imports);
        }
    }

    private void syncMethodSnippet(MethodDeclaration method, Map<String, ImportDeclaration> imports) {
        syncClassSnippet(AFTER_METHOD, method.toString(), imports);
    }

    private void syncInnerClassOrInterfaceOrEnumSnippet(BodyDeclaration<?> member, Map<String, ImportDeclaration> imports) {
        syncClassSnippet(AFTER_METHOD, member.toString(), imports);
    }

    private void syncClassOrInterfaceOrEnumSnippet(TypeDeclaration<?> type, Map<String, ImportDeclaration> imports) {
        syncClassSnippet(AFTER_CLASS, type.toString(), imports);
    }

    private void syncClassSnippet(ClassSnippetLocationType locationType, String snippet, Map<String, ImportDeclaration> imports) {
        syncImportSnippet(snippet, imports);
        javaClass.addRuntimeSnippet(new ClassSnippet(snippet, locationType));
    }

    private void syncImportSnippet(String snippet, Map<String, ImportDeclaration> imports) {
        imports.keySet()
                .stream()
                .filter(snippet::contains)
                .map(imports::get)
                .map(importClass -> new ClassSnippet(importClass.getNameAsString(), IMPORT))
                .forEach(javaClass::addRuntimeSnippet);
    }
}