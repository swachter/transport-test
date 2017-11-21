package eu.swdev.ttest.web;

import eu.swdev.ttest.server.Server;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

public class Scl implements ServletContextListener {

  volatile Server server = null;

  public void contextInitialized(ServletContextEvent servletContextEvent) {
    try {
      server = new Server();
      server.start();
      servletContextEvent.getServletContext().setAttribute("server", server);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

  }

  public void contextDestroyed(ServletContextEvent servletContextEvent) {
    if (server != null) {
      server.stop();
      server = null;
    }
  }
}
