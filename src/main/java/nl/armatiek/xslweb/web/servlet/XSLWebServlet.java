package nl.armatiek.xslweb.web.servlet;

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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.Validator;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ProxyWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.saxon.om.NodeInfo;
import net.sf.saxon.s9api.Destination;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.Serializer.Property;
import net.sf.saxon.s9api.TeeDestination;
import net.sf.saxon.s9api.XdmDestination;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import net.sf.saxon.s9api.Xslt30Transformer;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.serialize.MessageWarner;
import net.sf.saxon.stax.XMLStreamWriterDestination;
import nl.armatiek.xslweb.configuration.Context;
import nl.armatiek.xslweb.configuration.Definitions;
import nl.armatiek.xslweb.configuration.WebApp;
import nl.armatiek.xslweb.error.XSLWebException;
import nl.armatiek.xslweb.pipeline.FopSerializerStep;
import nl.armatiek.xslweb.pipeline.JSONSerializerStep;
import nl.armatiek.xslweb.pipeline.PipelineHandler;
import nl.armatiek.xslweb.pipeline.PipelineStep;
import nl.armatiek.xslweb.pipeline.ResponseStep;
import nl.armatiek.xslweb.pipeline.SchemaValidatorStep;
import nl.armatiek.xslweb.pipeline.SerializerStep;
import nl.armatiek.xslweb.pipeline.SystemTransformerStep;
import nl.armatiek.xslweb.pipeline.TransformerStep;
import nl.armatiek.xslweb.pipeline.ZipSerializerStep;
import nl.armatiek.xslweb.saxon.destination.SourceDestination;
import nl.armatiek.xslweb.saxon.destination.TeeSourceDestination;
import nl.armatiek.xslweb.saxon.destination.XdmSourceDestination;
import nl.armatiek.xslweb.saxon.errrorlistener.TransformationErrorListener;
import nl.armatiek.xslweb.saxon.errrorlistener.ValidatorErrorHandler;
import nl.armatiek.xslweb.utils.Closeable;
import nl.armatiek.xslweb.utils.XSLWebUtils;
import nl.armatiek.xslweb.xml.CleanupXMLStreamWriter;

public class XSLWebServlet extends HttpServlet {
  
  private static final long serialVersionUID = 1L;
  
  private static final Logger logger = LoggerFactory.getLogger(XSLWebServlet.class);
    
  private File homeDir;    
  
  public void init() throws ServletException {    
    super.init();   
    try {                        
      homeDir = Context.getInstance().getHomeDir();      
    } catch (Exception e) {
      logger.error(e.getMessage());
      throw new ServletException(e);
    }
  }
  
  @SuppressWarnings("unchecked")
  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
    OutputStream respOs = resp.getOutputStream();    
    WebApp webApp = null;
    try {            
      webApp = (WebApp) req.getAttribute(Definitions.ATTRNAME_WEBAPP);
      if (webApp.isClosed()) {
        resp.resetBuffer();
        resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        resp.setContentType("text/html; charset=UTF-8");
        Writer w = new OutputStreamWriter(respOs, "UTF-8");
        w.write("<html><body><h1>Service temporarily unavailable</h1></body></html>");
        return;
      }      
      executeRequest(webApp, req, resp, respOs);         
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      if (webApp != null && webApp.getDevelopmentMode()) {              
        resp.setContentType("text/plain; charset=UTF-8");        
        e.printStackTrace(new PrintStream(respOs));        
      } else if (!resp.isCommitted()) {
        resp.resetBuffer();
        resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        resp.setContentType("text/html; charset=UTF-8");
        Writer w = new OutputStreamWriter(respOs, "UTF-8");
        w.write("<html><body><h1>Internal Server Error</h1></body></html>");
      }
    } finally {
      // Delete any registered temporary files:
      try {
        List<File> tempFiles = (List<File>) req.getAttribute(Definitions.ATTRNAME_TEMPFILES);                        
        if (tempFiles != null) {
          ListIterator<File> li = tempFiles.listIterator();
          while(li.hasNext()) {
            File file;
            if ((file = li.next()) != null) {
              FileUtils.deleteQuietly(file);
            }           
          }                    
        }
      } catch (Exception se) {
        logger.error("Error deleting registered temporary files", se);
      }
      
      // Close any closeables:
      try {
        List<Closeable> closeables = (List<Closeable>) req.getAttribute("xslweb-closeables");                        
        if (closeables != null) {
          ListIterator<Closeable> li = closeables.listIterator(closeables.size());
          while(li.hasPrevious()) {
            li.previous().close();
          }                    
        }
      } catch (Exception se) {
        logger.error("Could not close Closeable", se);
      }
    }    
  }

  private Destination getDestination(WebApp webApp, Destination destination, PipelineStep step) {
    if (webApp.getDevelopmentMode() && step.getLog()) {
      StringWriter sw = new StringWriter();
      sw.write("----------\n");
      sw.write("OUTPUT OF STEP: \"" + step.getName() + "\":\n");            
      Serializer debugSerializer = webApp.getProcessor().newSerializer(new ProxyWriter(sw) {
        @Override
        public void flush() throws IOException {        
          logger.debug(out.toString());                
        }
      });                
      debugSerializer.setOutputProperty(Serializer.Property.METHOD, "xml");
      debugSerializer.setOutputProperty(Serializer.Property.INDENT, "yes");
      if (destination instanceof XdmDestination) {
        destination = new TeeSourceDestination((XdmDestination) destination, debugSerializer);
      } else {
        destination = new TeeDestination(destination, debugSerializer);
      }
    }
    return destination;
  }
  
  private Properties getOutputProperties(WebApp webApp, ErrorListener errorListener, List<PipelineStep> steps) throws Exception {
    for (int i=steps.size()-1; i>=0; i--) {
      PipelineStep step = steps.get(i);
      if (step instanceof TransformerStep) {
        String xslPath = ((TransformerStep) step).getXslPath();
        XsltExecutable executable = webApp.getTemplates(xslPath, errorListener);
        return executable.getUnderlyingCompiledStylesheet().getOutputProperties();
      }
    }
    return null;
  }
  
  private void addResponseTransformationStep(List<PipelineStep> steps) {
    SystemTransformerStep step = new SystemTransformerStep("system/response/response.xsl", "client-response", false);
    if (steps.get(steps.size()-1) instanceof SerializerStep) {
      steps.add(steps.size()-1, step);
    } else {
      steps.add(step);
    }
  }
  
  private Destination getDestination(WebApp webApp, HttpServletRequest req, HttpServletResponse resp, 
      OutputStream os, Properties outputProperties, PipelineStep currentStep, PipelineStep nextStep) throws Exception {
    Destination dest;
    if (nextStep == null) {      
      Serializer serializer = webApp.getProcessor().newSerializer(os);
      if (outputProperties != null) {
        for (String key : outputProperties.stringPropertyNames()) {
          String value = outputProperties.getProperty(key);
          if (key.equals(OutputKeys.CDATA_SECTION_ELEMENTS)) {
            value = value.replaceAll("\\{\\}", "{''}");
          }
          Property prop = Property.get(key);
          if (prop == null) {
            continue;          
          }
          serializer.setOutputProperty(prop, value);                      
        }
      }
      XMLStreamWriter xsw = new CleanupXMLStreamWriter(serializer.getXMLStreamWriter());
      dest = new XMLStreamWriterDestination(xsw);
    } else if (nextStep instanceof TransformerStep || nextStep instanceof SchemaValidatorStep) {
      dest = new XdmSourceDestination();
    } else if (nextStep instanceof JSONSerializerStep) {
      dest = ((JSONSerializerStep) nextStep).getDestination(webApp, req, resp, os, outputProperties);             
    } else if (nextStep instanceof ZipSerializerStep) {
      dest = ((ZipSerializerStep) nextStep).getDestination(webApp, req, resp, os, outputProperties);
    } else if (nextStep instanceof FopSerializerStep) {
      dest = ((FopSerializerStep) nextStep).getDestination(webApp, req, resp, os, outputProperties);
    } else {
      throw new XSLWebException("Could not determine destination");
    }
    return getDestination(webApp, dest, currentStep);
  }
  
  private void executeRequest(WebApp webApp, HttpServletRequest req, HttpServletResponse resp, 
      OutputStream respOs) throws Exception {        
    boolean developmentMode = webApp.getDevelopmentMode();               
    
    String requestXML = (String) req.getAttribute(Definitions.ATTRNAME_REQUESTXML);
    
    PipelineHandler pipelineHandler = (PipelineHandler) req.getAttribute(Definitions.ATTRNAME_PIPELINEHANDLER);
         
    ErrorListener errorListener = new TransformationErrorListener(resp, developmentMode);      
    MessageWarner messageWarner = new MessageWarner();
    
    List<PipelineStep> steps = pipelineHandler.getPipelineSteps();
    if (steps == null || steps.isEmpty()) {
      resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Resource not found");
      return;
    }
    
    OutputStream os = (developmentMode) ? new ByteArrayOutputStream() : respOs;
    
    Properties outputProperties = getOutputProperties(webApp, errorListener, steps);
    
    Map<QName, XdmValue> baseStylesheetParameters = XSLWebUtils.getStylesheetParameters(webApp, req, resp, homeDir);
    
    addResponseTransformationStep(steps);
    
    Map<QName, XdmValue> extraStylesheetParameters = null;
    Source source = new StreamSource(new StringReader(requestXML));
    Destination destination = null;
    
    for (int i=0; i<steps.size(); i++) {
      PipelineStep step = steps.get(i);
      if (step instanceof SerializerStep) {
        break;
      }
      PipelineStep nextStep = (i<steps.size()-1) ? steps.get(i+1) : null;
      if (step instanceof TransformerStep) {
        String xslPath = null;
        if (step instanceof SystemTransformerStep) {
          xslPath = new File(homeDir, "common/xsl/" + ((TransformerStep) step).getXslPath()).getAbsolutePath();                      
        } else {          
          xslPath = ((TransformerStep) step).getXslPath();
        }
        XsltExecutable templates = webApp.getTemplates(xslPath, errorListener); 
        Xslt30Transformer transformer = templates.load30();
        transformer.getUnderlyingController().setMessageEmitter(messageWarner);
        transformer.setErrorListener(errorListener);
        Map<QName, XdmValue> stylesheetParameters = new HashMap<QName, XdmValue>();
        stylesheetParameters.putAll(baseStylesheetParameters);
        XSLWebUtils.addStylesheetParameters(stylesheetParameters, ((TransformerStep) step).getParameters());
        if (extraStylesheetParameters != null) {
          stylesheetParameters.putAll(extraStylesheetParameters);
          extraStylesheetParameters.clear();
        }
        transformer.setStylesheetParameters(stylesheetParameters);
        destination = getDestination(webApp, req, resp, os, outputProperties, step, nextStep); 
        transformer.applyTemplates(source, destination);
      } else if (step instanceof SchemaValidatorStep) {
        SchemaValidatorStep svStep = (SchemaValidatorStep) step;
        List<String> schemaPaths = svStep.getSchemaPaths();
        Schema schema = webApp.getSchema(schemaPaths, errorListener);
        
        // if (source instanceof NodeInfo) {
        //  source = new DOMSource(NodeOverNodeInfo.wrap((NodeInfo) source));
        // }
        
        // Document resultDoc = XMLUtils.getDocumentBuilder(false, true).newDocument();
        // Result result = new DOMResult(resultDoc);
        
        if (source instanceof NodeInfo) {
          Serializer serializer = webApp.getProcessor().newSerializer();
          ByteArrayOutputStream sourceStream = new ByteArrayOutputStream();
          serializer.setOutputStream(sourceStream);
          serializer.setOutputProperty(Property.INDENT, "yes");
          serializer.serializeNode(new XdmNode((NodeInfo) source));
          source = new StreamSource(new ByteArrayInputStream(sourceStream.toByteArray()));
        }
        
        destination = null;
        ByteArrayOutputStream resultStream = new ByteArrayOutputStream();
        Result result = new StreamResult(resultStream);
        
        Validator validator = schema.newValidator();
        ValidatorErrorHandler errorHandler = new ValidatorErrorHandler("Step: " + ((step.getName() != null) ? step.getName() : "noname"));
        validator.setErrorHandler(errorHandler);
        
        // validator.setFeature(name, value);
        // validator.setProperty(name, object);
        
        validator.validate(source, result);
        
        source = new StreamSource(new ByteArrayInputStream(resultStream.toByteArray()));
        
        Source resultsSource = errorHandler.getValidationResults();
        if (resultsSource != null) {
          NodeInfo resultsNodeInfo = webApp.getConfiguration().buildDocumentTree(resultsSource).getRootNode();
          String xslParamName = svStep.getXslParamName();
          if (xslParamName != null) {
            if (extraStylesheetParameters == null) {
              extraStylesheetParameters = new HashMap<QName, XdmValue>();
            }
            extraStylesheetParameters.put(new QName(svStep.getXslParamNamespace(), xslParamName), 
                new XdmNode(resultsNodeInfo));
          }
        }
      } else if (step instanceof ResponseStep) {
        source = new StreamSource(new StringReader(((ResponseStep) step).getResponse()));
        continue;
      }
      
      if (destination instanceof SourceDestination) {
        /* Set source for next pipeline step: */
        source = ((SourceDestination) destination).asSource();
      }
    }
    
    if (developmentMode) {                       
      byte[] body = ((ByteArrayOutputStream) os).toByteArray();                         
      IOUtils.copy(new ByteArrayInputStream(body), respOs);
    }                      
  }
  
  /*
  private void executeRequestOld(WebApp webApp, HttpServletRequest req, HttpServletResponse resp, 
      OutputStream respOs) throws Exception {        
    boolean developmentMode = webApp.getDevelopmentMode();               
    
    String requestXML = (String) req.getAttribute(Definitions.ATTRNAME_REQUESTXML);
    
    PipelineHandler pipelineHandler = (PipelineHandler) req.getAttribute(Definitions.ATTRNAME_PIPELINEHANDLER);
         
    ErrorListener errorListener = new TransformationErrorListener(resp, developmentMode);      
    MessageWarner messageWarner = new MessageWarner();
    
    List<PipelineStep> steps = pipelineHandler.getPipelineSteps();
    if (steps == null || steps.isEmpty()) {
      resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Resource not found");
      return;
    }
    
    steps.add(new SystemTransformerStep("system/response/response.xsl", "client-response", false));
                     
    ArrayList<XsltExecutable> executables = new ArrayList<XsltExecutable>();
    ArrayList<XsltTransformer> transformers = new ArrayList<XsltTransformer>();
    SerializerStep serializerStep = null;
    for (int i=0; i<steps.size(); i++) {
      PipelineStep step = steps.get(i);          
      String xslPath = null;
      if (step instanceof SystemTransformerStep) {
        xslPath = new File(homeDir, "common/xsl/" + ((TransformerStep) step).getXslPath()).getAbsolutePath();                      
      } else if (step instanceof TransformerStep) {          
        xslPath = ((TransformerStep) step).getXslPath();            
      } else if (step instanceof ResponseStep) {
        requestXML = ((ResponseStep) step).getResponse();
        continue;
      } else if (step instanceof SerializerStep) {
        serializerStep = (SerializerStep) step;
        continue;
      }
      XsltExecutable templates = webApp.getTemplates(xslPath, errorListener);       
      XsltTransformer transformer = templates.load();
      transformer.getUnderlyingController().setMessageEmitter(messageWarner);
      XSLWebUtils.setPropertyParameters(transformer, webApp, homeDir);
      XSLWebUtils.setObjectParameters(transformer, webApp, req, resp);
      XSLWebUtils.setParameters(transformer, webApp.getParameters());
      XSLWebUtils.setParameters(transformer, ((TransformerStep) step).getParameters()); 
      transformer.setErrorListener(errorListener);
      if (!transformers.isEmpty()) {                                        
        PipelineStep prevStep = steps.get(i-1);
        Destination destination = getDestination(webApp, transformer, prevStep);
        transformers.get(transformers.size()-1).setDestination(destination);
      }                
      transformers.add(transformer);
      executables.add(templates);
    }
    
    XsltTransformer firstTransformer = transformers.get(0);      
    XsltTransformer lastTransformer = transformers.get(transformers.size()-1);
    
    Properties outputProperties = null;
    if (executables.size() >= 2) {
      XsltExecutable lastUserExecutable = executables.get(executables.size()-2);
      outputProperties = lastUserExecutable.getUnderlyingCompiledStylesheet().getOutputProperties();      
    }
          
    OutputStream os = (developmentMode) ? new ByteArrayOutputStream() : respOs;
    
    Destination dest = null;
    if (serializerStep == null) {      
      Serializer serializer = webApp.getProcessor().newSerializer(os);
      if (outputProperties != null) {
        for (String key : outputProperties.stringPropertyNames()) {
          String value = outputProperties.getProperty(key);
          if (key.equals(OutputKeys.CDATA_SECTION_ELEMENTS)) {
            value = value.replaceAll("\\{\\}", "{''}");
          }
          Property prop = Property.get(key);
          if (prop == null) {
            continue;          
          }
          serializer.setOutputProperty(prop, value);                      
        }
      }
      XMLStreamWriter xsw = new CleanupXMLStreamWriter(serializer.getXMLStreamWriter());
      dest = new XMLStreamWriterDestination(xsw);
    } else if (serializerStep instanceof JSONSerializerStep) {
      dest = ((JSONSerializerStep) serializerStep).getDestination(webApp, req, resp, os, outputProperties);             
    } else if (serializerStep instanceof ZipSerializerStep) {
      dest = ((ZipSerializerStep) serializerStep).getDestination(webApp, req, resp, os, outputProperties);
    } else if (serializerStep instanceof FopSerializerStep) {
      dest = ((FopSerializerStep) serializerStep).getDestination(webApp, req, resp, os, outputProperties);
    }
    
    Destination destination = getDestination(webApp, dest, steps.get(steps.size()-1));
                
    lastTransformer.setDestination(destination);
    firstTransformer.setSource(new StreamSource(new StringReader(requestXML)));                 
    firstTransformer.transform();
    
    if (developmentMode) {                       
      byte[] body = ((ByteArrayOutputStream) os).toByteArray();                         
      IOUtils.copy(new ByteArrayInputStream(body), respOs);
    }                      
  }
  */
  
}