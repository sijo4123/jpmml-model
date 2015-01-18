/*
 * Copyright (c) 2013 Villu Ruusmann
 */
package org.jpmml.xjc;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

import com.sun.codemodel.ClassType;
import com.sun.codemodel.JBlock;
import com.sun.codemodel.JClass;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JEnumConstant;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JExpression;
import com.sun.codemodel.JFieldRef;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JForLoop;
import com.sun.codemodel.JJavaName;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JPackage;
import com.sun.codemodel.JType;
import com.sun.codemodel.JVar;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;
import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.outline.FieldOutline;
import com.sun.tools.xjc.outline.Outline;
import com.sun.tools.xjc.util.CodeModelClassFactory;
import com.sun.xml.bind.api.impl.NameConverter;
import org.xml.sax.ErrorHandler;

public class VisitorPlugin extends Plugin {

	@Override
	public String getOptionName(){
		return "Xvisitor";
	}

	@Override
	public String getUsage(){
		return null;
	}

	@Override
	public boolean run(Outline outline, Options options, ErrorHandler errorHandler){
		JCodeModel codeModel = outline.getCodeModel();

		CodeModelClassFactory clazzFactory = outline.getClassFactory();

		JClass objectClazz = codeModel.ref(Object.class);

		JClass pmmlObjectClazz = codeModel.ref("org.dmg.pmml.PMMLObject");
		JClass visitableInterface = codeModel.ref("org.dmg.pmml.Visitable");

		JClass dequeClazz = codeModel.ref(Deque.class);
		JClass dequeImplementationClazz = codeModel.ref(ArrayDeque.class);

		JPackage pmmlPackage = pmmlObjectClazz._package();

		JDefinedClass visitorActionClazz = clazzFactory.createClass(pmmlPackage, JMod.PUBLIC, "VisitorAction", null, ClassType.ENUM);
		JEnumConstant continueAction = visitorActionClazz.enumConstant("CONTINUE");
		JEnumConstant skipAction = visitorActionClazz.enumConstant("SKIP");
		JEnumConstant terminateAction = visitorActionClazz.enumConstant("TERMINATE");

		JDefinedClass visitorInterface = clazzFactory.createClass(pmmlPackage, JMod.PUBLIC, "Visitor", null, ClassType.INTERFACE);
		visitorInterface.javadoc().append("@see PMMLObject#accept(Visitor)");

		JMethod visitorPushParent = visitorInterface.method(JMod.PUBLIC, void.class, "pushParent");
		visitorPushParent.param(pmmlObjectClazz, "object");

		JMethod visitorPopParent = visitorInterface.method(JMod.PUBLIC, void.class, "popParent");

		JPackage visitorPackage = codeModel._package("org.jpmml.model.visitors");

		JDefinedClass abstractVisitorClazz = clazzFactory.createClass(visitorPackage, JMod.ABSTRACT | JMod.PUBLIC, "AbstractVisitor", null, ClassType.CLASS)._implements(visitorInterface);
		createPathMethods(abstractVisitorClazz, dequeClazz, dequeImplementationClazz, pmmlObjectClazz);

		JDefinedClass abstractSimpleVisitorClazz = clazzFactory.createClass(visitorPackage, JMod.ABSTRACT | JMod.PUBLIC, "AbstractSimpleVisitor", null, ClassType.CLASS)._implements(visitorInterface);
		createPathMethods(abstractSimpleVisitorClazz, dequeClazz, dequeImplementationClazz, pmmlObjectClazz);

		JMethod defaultMethod = abstractSimpleVisitorClazz.method(JMod.PUBLIC, visitorActionClazz, "visit");
		defaultMethod.param(pmmlObjectClazz, "object");
		defaultMethod.body()._return(continueAction);

		Set<JType> traversableTypes = new LinkedHashSet<JType>();

		Collection<? extends ClassOutline> clazzes = outline.getClasses();
		for(ClassOutline clazz : clazzes){
			JDefinedClass beanClazz = clazz.implClass;
			traversableTypes.add(beanClazz);

			JClass beanSuperClazz = beanClazz._extends();
			traversableTypes.add(beanSuperClazz);
		} // End for

		for(ClassOutline clazz : clazzes){
			JDefinedClass beanClazz = clazz.implClass;

			String parameterName = NameConverter.standard.toVariableName(beanClazz.name());
			if(!JJavaName.isJavaIdentifier(parameterName)){
				parameterName = ("_" + parameterName);
			}

			JMethod visitorVisit = visitorInterface.method(JMod.PUBLIC, visitorActionClazz, "visit");
			visitorVisit.param(beanClazz, parameterName);

			JMethod abstractVisitorVisit = abstractVisitorClazz.method(JMod.PUBLIC, visitorActionClazz, "visit");
			abstractVisitorVisit.annotate(Override.class);
			abstractVisitorVisit.param(beanClazz, parameterName);
			abstractVisitorVisit.body()._return(continueAction);

			JClass beanSuperClass = beanClazz._extends();

			JMethod abstractSimpleVisitorVisit = abstractSimpleVisitorClazz.method(JMod.PUBLIC, visitorActionClazz, "visit");
			abstractSimpleVisitorVisit.annotate(Override.class);
			abstractSimpleVisitorVisit.param(beanClazz, parameterName);
			abstractSimpleVisitorVisit.body()._return(JExpr.invoke(defaultMethod).arg(JExpr.cast(beanSuperClass, JExpr.ref(parameterName))));

			JMethod beanAccept = beanClazz.method(JMod.PUBLIC, visitorActionClazz, "accept");
			beanAccept.annotate(Override.class);

			JVar visitorParameter = beanAccept.param(visitorInterface, "visitor");

			JBlock body = beanAccept.body();

			JVar status = body.decl(visitorActionClazz, "status", JExpr.invoke(visitorParameter, "visit").arg(JExpr._this()));

			FieldOutline[] fields = clazz.getDeclaredFields();
			if(fields.length > 0){
				body.add(JExpr.invoke(visitorParameter, visitorPushParent).arg(JExpr._this()));
			}

			for(FieldOutline field : fields){
				CPropertyInfo propertyInfo = field.getPropertyInfo();

				String fieldName = propertyInfo.getName(false);

				JFieldRef fieldRef = JExpr.refthis(fieldName);

				JType fieldType = field.getRawType();

				// Collection of values
				if(propertyInfo.isCollection()){
					JType fieldElementType = CodeModelUtil.getElementType(fieldType);

					if(traversableTypes.contains(fieldElementType) || objectClazz.equals(fieldElementType)){
						JForLoop forLoop = body._for();
						JVar var = forLoop.init(codeModel.INT, "i", JExpr.lit(0));
						forLoop.test((status.eq(continueAction)).cand(fieldRef.ne(JExpr._null())).cand(var.lt(fieldRef.invoke("size"))));
						forLoop.update(var.incr());

						JExpression getElement = (JExpr.invoke(fieldRef, "get")).arg(var);

						if(traversableTypes.contains(fieldElementType)){
							forLoop.body().assign(status, getElement.invoke("accept").arg(visitorParameter));
						} else

						if(objectClazz.equals(fieldElementType)){
							forLoop.body()._if(getElement._instanceof(visitableInterface))._then().assign(status, ((JExpression)JExpr.cast(visitableInterface, getElement)).invoke("accept").arg(visitorParameter));
						}
					}
				} else

				// Simple value
				{
					if(traversableTypes.contains(fieldType)){
						body._if((status.eq(continueAction)).cand(fieldRef.ne(JExpr._null())))._then().assign(status, JExpr.invoke(fieldRef, "accept").arg(visitorParameter));
					}
				}
			}

			if(fields.length > 0){
				body.add(JExpr.invoke(visitorParameter, visitorPopParent));
			}

			body._if(status.eq(terminateAction))._then()._return(terminateAction);

			body._return(continueAction);
		}

		return true;
	}

	static
	private void createPathMethods(JDefinedClass visitorClazz, JClass dequeClazz, JClass dequeImplementationClazz, JClass pmmlObjectClazz){
		JFieldVar parents = visitorClazz.field(JMod.PRIVATE, dequeClazz.narrow(pmmlObjectClazz), "parents", JExpr._new(dequeImplementationClazz.narrow(pmmlObjectClazz)));

		JMethod pushParent = visitorClazz.method(JMod.PUBLIC, void.class, "pushParent");
		pushParent.annotate(Override.class);
		JVar parent = pushParent.param(pmmlObjectClazz, "parent");
		pushParent.body().add(parents.invoke("addFirst").arg(parent));

		JMethod popParent = visitorClazz.method(JMod.PUBLIC, void.class, "popParent");
		popParent.annotate(Override.class);
		popParent.body().add(parents.invoke("removeFirst"));

		JMethod getParents = visitorClazz.method(JMod.PUBLIC, dequeClazz.narrow(pmmlObjectClazz), "getParents");
		getParents.body()._return(parents);
	}
}