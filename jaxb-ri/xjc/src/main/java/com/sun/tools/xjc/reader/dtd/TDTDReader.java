/*
 * Copyright (c) 1997, 2023 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0, which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package com.sun.tools.xjc.reader.dtd;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

import javax.xml.namespace.QName;

import com.sun.codemodel.JClass;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JPackage;
import com.sun.tools.xjc.AbortException;
import com.sun.tools.xjc.ErrorReceiver;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;
import com.sun.tools.xjc.model.CAttributePropertyInfo;
import com.sun.tools.xjc.model.CBuiltinLeafInfo;
import com.sun.tools.xjc.model.CClassInfo;
import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.tools.xjc.model.Model;
import com.sun.tools.xjc.model.TypeUse;
import com.sun.tools.xjc.model.TypeUseFactory;
import com.sun.tools.xjc.model.CDefaultValue;
import com.sun.tools.xjc.reader.ModelChecker;
import com.sun.tools.xjc.reader.Ring;
import com.sun.tools.xjc.reader.dtd.bindinfo.BIAttribute;
import com.sun.tools.xjc.reader.dtd.bindinfo.BIElement;
import com.sun.tools.xjc.reader.dtd.bindinfo.BIInterface;
import com.sun.tools.xjc.reader.dtd.bindinfo.BindInfo;
import com.sun.tools.xjc.util.CodeModelClassFactory;
import com.sun.tools.xjc.util.ErrorReceiverFilter;
import org.glassfish.jaxb.core.api.impl.NameConverter;
import com.sun.xml.dtdparser.DTDHandlerBase;
import com.sun.xml.dtdparser.DTDParser;
import com.sun.xml.dtdparser.InputEntity;
import com.sun.xml.xsom.XmlString;
import com.sun.istack.SAXParseException2;

import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.LocatorImpl;

/**
 * Parses DTD grammar along with binding information into BGM.
 * 
 * @author
 *    <a href="mailto:kohsuke.kawaguchi@sun.com">Kohsuke KAWAGUCHI</a>
 */
public class TDTDReader extends DTDHandlerBase
{
    /**
     * Parses DTD grammar and a binding information into BGM.
     * 
     * <p>
     * This method is just a utility method that covers 80% of the use
     * cases.
     * 
     * @param    bindingInfo
     *        binding information file, if any. Can be null.
     */
    public static Model parse(
        InputSource dtd,
        InputSource bindingInfo,
        ErrorReceiver errorReceiver,
        Options opts) {

        try {
            // set up a ring
            final Ring old = Ring.begin();
            try {
                ErrorReceiverFilter ef = new ErrorReceiverFilter(errorReceiver);

                JCodeModel cm = new JCodeModel();
                Model model = new Model(opts,cm,NameConverter.standard,opts.classNameAllocator,null);

                Ring.add(cm);
                Ring.add(model);
                Ring.add(ErrorReceiver.class,ef);

                TDTDReader reader = new TDTDReader( ef, opts, bindingInfo);

                DTDParser parser = new DTDParser();
                parser.setDtdHandler(reader);
                if( opts.entityResolver!=null )
                    parser.setEntityResolver(opts.entityResolver);

                try {
                    parser.parse(dtd);
                } catch (SAXParseException e) {
                    return null; // this error was already handled by GrammarReaderController
                }

                Ring.get(ModelChecker.class).check();

                if( opts.activePlugins!=null ) {
                    for (Plugin ma : opts.activePlugins)
                        ma.postProcessModel(model, Ring.get(ErrorReceiver.class));
                }

                if(ef.hadError())   return null;
                else                return model;
            } finally {
                Ring.end(old);
            }
        } catch (IOException | SAXException e) {
            errorReceiver.error(new SAXParseException2(e.getMessage(),null,e));
            return null;
        } catch (AbortException e) {
            // parsing was aborted but the error was already reported
            return null;
        }
    }
    protected TDTDReader(ErrorReceiver errorReceiver, Options opts, InputSource _bindInfo)
        throws AbortException {
        this.entityResolver = opts.entityResolver;
        this.errorReceiver = new ErrorReceiverFilter(errorReceiver);
        bindInfo = new BindInfo(model,_bindInfo, this.errorReceiver);
        classFactory = new CodeModelClassFactory(errorReceiver);
    }

    private final EntityResolver entityResolver;

    /**
     * binding information.
     * 
     * <p>
     * This is always non-null even if no binding information was specified.
     * (In that case, a dummy object will be provided.)
     */
    final BindInfo bindInfo;

    final Model model = Ring.get(Model.class);

    private final CodeModelClassFactory classFactory;
    
    private final ErrorReceiverFilter errorReceiver;

    /**
     * Element name to its content model definition.
     */
    private final Map<String,Element> elements = new HashMap<>();


    @Override
    public void startDTD(InputEntity entity) throws SAXException {
    }
    
    @Override
    public void endDTD() throws SAXException {

        // bind them all.
        // we need to know how elements are referencing each other before we do this,
        // so this can be only done at the endDTD method
        for( Element e : elements.values() )
            e.bind();

        // if there was an error by now, just abort.
        if (errorReceiver.hadError())
            return;

        processInterfaceDeclarations();

        // check XJC extensions and realize them
        model.serialVersionUID = bindInfo.getSerialVersionUID();
        if(model.serialVersionUID!=null)
            model.serializable=true;
        model.rootClass = bindInfo.getSuperClass();
        model.rootInterface = bindInfo.getSuperInterface();

// TODO: do we need to reimplement them?
//        // performs annotation
//        Annotator.annotate(model, this);
//        FieldCollisionChecker.check( model, this );
        
        
        processConstructorDeclarations();
    }

    /** Processes interface declarations. */
    private void processInterfaceDeclarations() {


        Map<String,InterfaceAcceptor> fromName = new HashMap<>();

        // first, create empty InterfaceItem declaration for all interfaces
        Map<BIInterface,JClass> decls = new HashMap<>();

        for( BIInterface decl : bindInfo.interfaces() ) {
            final JDefinedClass intf = classFactory.createInterface(
                                bindInfo.getTargetPackage(), decl.name(), copyLocator() );
            decls.put(decl,intf);
            fromName.put(decl.name(),new InterfaceAcceptor() {
                @Override
                public void implement(JClass c) {
                    intf._implements(c);
                }
            });
        }

        for( final CClassInfo ci : model.beans().values() ) {
            fromName.put(ci.getName(),new InterfaceAcceptor() {
                @Override
                public void implement(JClass c) {
                    ci._implements(c);
                }
            });
        }

        // traverse the interface declarations again
        // and populate its expression according to the members attribute.
        for( Map.Entry<BIInterface,JClass> e : decls.entrySet() ) {
            BIInterface decl = e.getKey();
            JClass c = e.getValue();

            for (String member : decl.members()) {
                InterfaceAcceptor acc = fromName.get(member);
                if (acc == null) {
                    // there is no such class/interface
                    // TODO: error location
                    error(decl.getSourceLocation(),
                            Messages.ERR_BINDINFO_NON_EXISTENT_INTERFACE_MEMBER,
                            member);
                    continue;
                }

                acc.implement(c);
            }
        }
        
        // TODO: check the cyclic interface definition
    }

    private interface InterfaceAcceptor {
        void implement( JClass c );
    }


    JPackage getTargetPackage() {
        return bindInfo.getTargetPackage();
    }
    
    
    /**
     * Creates constructor declarations as specified in the
     * binding information.
     * 
     * <p>
     * Also checks that the binding file does not contain
     * declarations for non-existent elements.
     */
    private void processConstructorDeclarations() {
        for( BIElement decl: bindInfo.elements() ) {
            Element e = elements.get(decl.name());
            if(e==null) {
                error(decl.getSourceLocation(),
                    Messages.ERR_BINDINFO_NON_EXISTENT_ELEMENT_DECLARATION,decl.name());
                continue;   // continue to process next declaration
            }
            
            if(!decl.isClass())
                // only element-class declaration has constructor definitions
                continue;
            
            decl.declareConstructors(e.getClassInfo());
        }
    }

    @Override
    public void attributeDecl(String elementName, String attributeName, String attributeType, String[] enumeration, short attributeUse, String defaultValue) throws SAXException {
        getOrCreateElement(elementName).attributes.add(
            createAttribute(elementName, attributeName, attributeType, enumeration, attributeUse, defaultValue)
        );
    }

    protected CPropertyInfo createAttribute(
        String elementName, String attributeName, String attributeType,
        String[] enums, short attributeUse, String defaultValue )
            throws SAXException {

        boolean required = attributeUse==USE_REQUIRED;

        // get the attribute-property declaration
        BIElement edecl = bindInfo.element(elementName);
        BIAttribute decl=null;
        if(edecl!=null)     decl=edecl.attribute(attributeName);

        String propName;
        if(decl==null)  propName = model.getNameConverter().toPropertyName(attributeName);
        else            propName = decl.getPropertyName();

        QName qname = new QName("",attributeName);

        // if no declaration is specified, just wrap it by
        // a FieldItem and let the normalizer handle its content.
        TypeUse use;

        if(decl!=null && decl.getConversion()!=null)
            use = decl.getConversion().getTransducer();
        else
            use = builtinConversions.get(attributeType);

        CPropertyInfo r = new CAttributePropertyInfo(
            propName, null,null/*TODO*/, copyLocator(), qname, use, null, required );

        if(defaultValue!=null)
            r.defaultValue = CDefaultValue.create( use, new XmlString(defaultValue) );

        return r;
    }



    Element getOrCreateElement( String elementName ) {
        Element r = elements.get(elementName);
        if(r==null) {
            r = new Element(this,elementName);
            elements.put(elementName,r);
        }

        return r;
    }


    @Override
    public void startContentModel(String elementName, short contentModelType) throws SAXException {
        assert modelGroups.isEmpty();
        modelGroups.push(new ModelGroup());
    }

    @Override
    public void endContentModel(String elementName, short contentModelType) throws SAXException {
        assert modelGroups.size()==1;
        Term term = modelGroups.pop().wrapUp();

        Element e = getOrCreateElement(elementName);
        e.define( contentModelType, term, copyLocator() );
    }


    private final Stack<ModelGroup> modelGroups = new Stack<>();

    @Override
    public void startModelGroup() throws SAXException {
        modelGroups.push(new ModelGroup());
    }

    @Override
    public void endModelGroup(short occurence) throws SAXException {
        Term t = Occurence.wrap( modelGroups.pop().wrapUp(), occurence );
        modelGroups.peek().addTerm(t);
    }

    @Override
    public void connector(short connectorType) throws SAXException {
        modelGroups.peek().setKind(connectorType);
    }

    // TODO: for now, we just ignore all the content model specification
    // and treat it as (A,B,C,....)

    @Override
    public void childElement(String elementName, short occurence) throws SAXException {
        Element child = getOrCreateElement(elementName);
        modelGroups.peek().addTerm( Occurence.wrap( child, occurence ) );
        child.isReferenced = true;
    }





    /**
     * Mutable {@link Locator} instance that points to
     * the current source line.
     * <p>
     * Use {@link #copyLocator()} to get a immutable clone.
     */
    private Locator locator;

    @Override
    public void setDocumentLocator(Locator loc) {
        this.locator = loc;
    }

    /**
     * Creates a snapshot of the current {@link #locator} values.
     */
    private Locator copyLocator(){
        return new LocatorImpl(locator);
    }



//
//
// builtin datatype handling
//
//

    /** Transducers for the built-in types. Read-only. */
    private static final Map<String,TypeUse> builtinConversions;


    static {
        // list of datatypes which have built-in conversions.
        // note that although xs:token and xs:normalizedString are not
        // specified in the spec, they need to be here because they
        // have different whitespace normalization semantics.
        Map<String,TypeUse> m = new HashMap<>();

        m.put("CDATA",      CBuiltinLeafInfo.NORMALIZED_STRING);
        m.put("ENTITY",     CBuiltinLeafInfo.TOKEN);
        m.put("ENTITIES",   CBuiltinLeafInfo.STRING.makeCollection());
        m.put("NMTOKEN",    CBuiltinLeafInfo.TOKEN);
        m.put("NMTOKENS",   CBuiltinLeafInfo.STRING.makeCollection());
        m.put("ID",         CBuiltinLeafInfo.ID);
        m.put("IDREF",      CBuiltinLeafInfo.IDREF);
        m.put("IDREFS",     TypeUseFactory.makeCollection(CBuiltinLeafInfo.IDREF));
        m.put("ENUMERATION",CBuiltinLeafInfo.TOKEN);

        builtinConversions = Collections.unmodifiableMap(m);
    }


//
//
// error related utility methods
//
//
    @Override
    public void error(SAXParseException e) throws SAXException {
        errorReceiver.error(e);
    }

    @Override
    public void fatalError(SAXParseException e) throws SAXException {
        errorReceiver.fatalError(e);
    }

    @Override
    public void warning(SAXParseException e) throws SAXException {
        errorReceiver.warning(e);
    }

    protected final void error( Locator loc, String prop, Object... args ) {
        errorReceiver.error(loc,Messages.format(prop,args));
    }

    
}
