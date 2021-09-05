/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt.internal;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import xsbti.*;

/**
 * Generates a new app configuration and invokes xMainImpl.run. For AppConfigurations generated by
 * recent launchers, it is unnecessary to modify the original configuration, but configurations
 * generated by older launchers need to be modified to place the test interface jar higher in the
 * class hierarchy. The methods this object are implemented without using the scala library so that
 * we can avoid loading any classes from the old scala provider.
 */
public class XMainConfiguration {
  public xsbti.MainResult run(String moduleName, xsbti.AppConfiguration configuration)
      throws Throwable {
    try {
      ClassLoader topLoader = configuration.provider().scalaProvider().launcher().topLoader();
      xsbti.AppConfiguration updatedConfiguration = null;
      try {
        Method method = topLoader.getClass().getMethod("getJLineJars");
        URL[] jars = (URL[]) method.invoke(topLoader);
        boolean canReuseConfiguration = jars.length == 3;
        int j = 0;
        while (j < jars.length && canReuseConfiguration) {
          String s = jars[j].toString();
          canReuseConfiguration = s.contains("jline") || s.contains("jansi");
          j += 1;
        }
        if (canReuseConfiguration && j == 3) {
          updatedConfiguration = configuration;
        } else {
          updatedConfiguration = makeConfiguration(configuration);
        }
      } catch (NoSuchMethodException e) {
        updatedConfiguration = makeConfiguration(configuration);
      }

      ClassLoader loader = updatedConfiguration.provider().loader();
      Thread.currentThread().setContextClassLoader(loader);
      Class<?> clazz = loader.loadClass("sbt." + moduleName + "$");
      Object instance = clazz.getField("MODULE$").get(null);
      Method runMethod = clazz.getMethod("run", xsbti.AppConfiguration.class);
      try {
        Class<?> clw = loader.loadClass("sbt.internal.ClassLoaderWarmup$");
        clw.getMethod("warmup").invoke(clw.getField("MODULE$").get(null));
        return (xsbti.MainResult) runMethod.invoke(instance, updatedConfiguration);
      } catch (InvocationTargetException e) {
        // This propogates xsbti.FullReload to the launcher
        throw e.getCause();
      }
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  private xsbti.AppConfiguration makeConfiguration(xsbti.AppConfiguration configuration) {
    try {
      ClassLoader baseLoader = XMainConfiguration.class.getClassLoader();
      String className = "sbt/internal/XMainConfiguration.class";
      URL url = baseLoader.getResource(className);
      String path = url.toString().replaceAll(className.concat("$"), "");
      URL[] urlArray = new URL[1];
      urlArray[0] = new URL(path);
      ClassLoader topLoader = configuration.provider().scalaProvider().launcher().topLoader();
      // This loader doesn't have the scala library in it so it's critical that none of the code
      // in this file use the scala library.
      ClassLoader modifiedLoader = new XMainClassLoader(urlArray, topLoader);
      Class<?> xMainConfigurationClass =
          modifiedLoader.loadClass("sbt.internal.XMainConfiguration");
      Object instance = (Object) xMainConfigurationClass.getConstructor().newInstance();
      Class<?> metaBuildLoaderClass = modifiedLoader.loadClass("sbt.internal.MetaBuildLoader");
      Method method = metaBuildLoaderClass.getMethod("makeLoader", AppProvider.class);

      ClassLoader loader = (ClassLoader) method.invoke(null, configuration.provider());

      Thread.currentThread().setContextClassLoader(loader);
      Class<?> modifiedConfigurationClass =
          modifiedLoader.loadClass("sbt.internal.XMainConfiguration$ModifiedConfiguration");
      Constructor<?> cons = modifiedConfigurationClass.getConstructors()[0];
      ClassLoaderClose.close(configuration.provider().loader());
      ScalaProvider scalaProvider = configuration.provider().scalaProvider();
      Class<? extends ScalaProvider> providerClass = scalaProvider.getClass();
      try {
        Method method2 = providerClass.getMethod("loaderLibraryOnly");
        ClassLoaderClose.close((ClassLoader) method2.invoke(scalaProvider));
      } catch (NoSuchMethodException e) {
      }
      ClassLoaderClose.close(scalaProvider.loader());
      ClassLoaderClose.close(configuration.provider().loader());
      return (xsbti.AppConfiguration) cons.newInstance(instance, configuration, loader);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  /*
   * Replaces the AppProvider.loader method with a new loader that puts the sbt test interface
   * jar ahead of the rest of the sbt classpath in the classloading hierarchy.
   */
  public class ModifiedConfiguration implements xsbti.AppConfiguration {
    private xsbti.AppConfiguration configuration;
    private ClassLoader metaLoader;

    public ModifiedConfiguration(xsbti.AppConfiguration configuration, ClassLoader metaLoader) {
      this.configuration = configuration;
      this.metaLoader = metaLoader;
    }

    public class ModifiedAppProvider implements AppProvider {
      private AppProvider appProvider;
      private ScalaProvider instance;

      public ModifiedAppProvider(AppProvider appProvider) throws ClassNotFoundException {
        this.appProvider = appProvider;
        ScalaProvider delegate = configuration.provider().scalaProvider();
        this.instance =
            new ScalaProvider() {
              public Launcher _launcher =
                  new Launcher() {
                    private Launcher delegateLauncher = delegate.launcher();
                    private ClassLoader interfaceLoader =
                        metaLoader.loadClass("sbt.testing.Framework").getClassLoader();

                    @Override
                    public ScalaProvider getScala(String version) {
                      return getScala(version, "");
                    }

                    @Override
                    public ScalaProvider getScala(String version, String reason) {
                      return getScala(version, reason, "org.scala-lang");
                    }

                    @Override
                    public ScalaProvider getScala(String version, String reason, String scalaOrg) {
                      return delegateLauncher.getScala(version, reason, scalaOrg);
                    }

                    @Override
                    public AppProvider app(xsbti.ApplicationID id, String version) {
                      return delegateLauncher.app(id, version);
                    }

                    @Override
                    public ClassLoader topLoader() {
                      return interfaceLoader;
                    }

                    @Override
                    public GlobalLock globalLock() {
                      return delegateLauncher.globalLock();
                    }

                    @Override
                    public File bootDirectory() {
                      return delegateLauncher.bootDirectory();
                    }

                    @Override
                    public xsbti.Repository[] ivyRepositories() {
                      return delegateLauncher.ivyRepositories();
                    }

                    @Override
                    public xsbti.Repository[] appRepositories() {
                      return delegateLauncher.appRepositories();
                    }

                    @Override
                    public boolean isOverrideRepositories() {
                      return delegateLauncher.isOverrideRepositories();
                    }

                    @Override
                    public File ivyHome() {
                      return delegateLauncher.ivyHome();
                    }

                    @Override
                    public String[] checksums() {
                      return delegateLauncher.checksums();
                    }
                  };

              @Override
              public Launcher launcher() {
                return this._launcher;
              }

              @Override
              public String version() {
                return delegate.version();
              }

              @Override
              public ClassLoader loader() {
                return metaLoader.getParent();
              }

              @Override
              public File[] jars() {
                return delegate.jars();
              }

              @Override
              @Deprecated()
              public File libraryJar() {
                return delegate.libraryJar();
              }

              @Override
              @Deprecated()
              public File compilerJar() {
                return delegate.compilerJar();
              }

              @Override
              public AppProvider app(xsbti.ApplicationID id) {
                return delegate.app(id);
              }

              private ClassLoader loaderLibraryOnly() {
                return metaLoader.getParent().getParent();
              }
            };
      }

      @Override
      public ScalaProvider scalaProvider() {
        return instance;
      }

      @Override
      public xsbti.ApplicationID id() {
        return appProvider.id();
      }

      @Override
      public ClassLoader loader() {
        return metaLoader;
      }

      @Override
      @Deprecated()
      public Class<? extends AppMain> mainClass() {
        return appProvider.mainClass();
      }

      @Override
      public Class<?> entryPoint() {
        return appProvider.entryPoint();
      }

      @Override
      public AppMain newMain() {
        return appProvider.newMain();
      }

      @Override
      public File[] mainClasspath() {
        return appProvider.mainClasspath();
      }

      @Override
      public ComponentProvider components() {
        return appProvider.components();
      }
    }

    @Override
    public String[] arguments() {
      return configuration.arguments();
    }

    @Override
    public File baseDirectory() {
      return configuration.baseDirectory();
    }

    @Override
    public AppProvider provider() {
      try {
        return new ModifiedAppProvider(configuration.provider());
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }
  }
}
