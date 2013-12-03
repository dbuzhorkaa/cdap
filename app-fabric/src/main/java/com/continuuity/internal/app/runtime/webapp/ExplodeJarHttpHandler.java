package com.continuuity.internal.app.runtime.webapp;

import com.continuuity.common.conf.Constants;
import com.continuuity.common.http.core.AbstractHttpHandler;
import com.continuuity.common.http.core.HandlerContext;
import com.continuuity.common.http.core.HttpResponder;
import com.continuuity.weave.filesystem.Location;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.io.Files;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.activation.MimetypesFileTypeMap;
import javax.annotation.Nullable;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.io.File;
import java.util.jar.JarEntry;

/**
 * Http service handler that serves files in deployed jar after exploding the jar.
 */
public class ExplodeJarHttpHandler extends AbstractHttpHandler implements JarHttpHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ExplodeJarHttpHandler.class);

  private static final MimetypesFileTypeMap mimeTypesMap = new MimetypesFileTypeMap();

  private final Location jarLocation;
  private ServePathGenerator servePathGenerator;

  @Inject
  public ExplodeJarHttpHandler(@Assisted Location jarLocation) {
    this.jarLocation = jarLocation;
  }

  @Override
  public void init(HandlerContext context) {
    super.init(context);

    // Setup program jar for serving
    try {
      File jarFile = new File(jarLocation.toURI());

      File baseDir = Files.createTempDir();
      int numFiles = JarExploder.explode(jarFile, baseDir, EXPLODE_FILTER);

      File serveDir = new File(baseDir, Constants.Webapp.WEBAPP_DIR);

      Predicate<String> fileExists = new Predicate<String>() {
        @Override
        public boolean apply(@Nullable String file) {
          return file != null && new File(file).exists();
        }
      };

      servePathGenerator = new ServePathGenerator(serveDir.getAbsolutePath(), fileExists);

      LOG.info("Exploded {} files from jar {}", numFiles, jarFile.getAbsolutePath());
    } catch (Throwable t) {
      LOG.error("Got exception: ", t);
      throw Throwables.propagate(t);
    }
  }

  @Override
  public String getServePath(String hostHeader, String uri) {
    return servePathGenerator.getServePath(hostHeader, uri);
  }

  @GET
  @Path("/.*")
  public void serve(HttpRequest request, HttpResponder responder) {
    try {

      String path = request.getUri();
      if (path == null) {
        responder.sendStatus(HttpResponseStatus.NOT_FOUND);
        return;
      }

      if (path.startsWith("/") && path.length() > 1) {
        path = path.substring(1);
      }

      File file = new File(path);
      if (!file.exists()) {
        responder.sendStatus(HttpResponseStatus.NOT_FOUND);
        return;
      }

      if (!file.isFile()) {
        responder.sendStatus(HttpResponseStatus.FORBIDDEN);
        return;
      }

      responder.sendFile(file, ImmutableMultimap.of(HttpHeaders.Names.CONTENT_TYPE,
                                                    mimeTypesMap.getContentType(file.getAbsolutePath())));

    } catch (Throwable t) {
      LOG.error("Got exception: ", t);
      responder.sendStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
    }
  }

  private static final Predicate<JarEntry> EXPLODE_FILTER = new Predicate<JarEntry>() {
    @Override
    public boolean apply(JarEntry input) {
      return input.getName().startsWith(Constants.Webapp.WEBAPP_DIR);
    }
  };
}
