package com.intellij.javascript.karma.server;

import com.intellij.concurrency.JobScheduler;
import com.intellij.javascript.karma.execution.KarmaServerSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.CatchingConsumer;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * @author Sergey Simonchik
 */
public class KarmaServerRegistry {

  private static final Logger LOG = Logger.getInstance(KarmaServerRegistry.class);

  private final Project myProject;
  private final ConcurrentMap<String, KarmaServer> myServerByConfigFile = new ConcurrentHashMap<>();
  private final ConcurrentMap<KarmaServerSettings, KarmaServer> myServers = new ConcurrentHashMap<>();
  private final ConcurrentMap<KarmaServerSettings, KarmaServerSettings> myStartingServers = new ConcurrentHashMap<>();

  public KarmaServerRegistry(@NotNull Project project) {
    myProject = project;
  }

  @NotNull
  public static KarmaServerRegistry getInstance(@NotNull Project project) {
    return ServiceManager.getService(project, KarmaServerRegistry.class);
  }

  @Nullable
  public KarmaServer getServer(@NotNull KarmaServerSettings serverSettings) {
    return myServers.get(serverSettings);
  }

  public void startServer(@NotNull final KarmaServerSettings serverSettings, final CatchingConsumer<KarmaServer, Exception> consumer) {
    KarmaServer prevServer = myServerByConfigFile.get(serverSettings.getConfigurationFilePath());
    if (prevServer != null) {
      prevServer.onTerminated(new KarmaServerTerminatedListener() {
        @Override
        public void onTerminated(int exitCode) {
          doStartServer(serverSettings, consumer);
        }
      });
      prevServer.shutdownAsync();
    }
    else {
      doStartServer(serverSettings, consumer);
    }
  }

  private void doStartServer(@NotNull final KarmaServerSettings serverSettings,
                             @NotNull final CatchingConsumer<KarmaServer, Exception> consumer) {
    if (myStartingServers.putIfAbsent(serverSettings, serverSettings) != null) {
      LOG.warn(new Throwable("Unexpected subsequent karma server starting:" + serverSettings.toString()));
      JobScheduler.getScheduler().schedule(() -> startServer(serverSettings, consumer), 100, TimeUnit.MILLISECONDS);
      return;
    }
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        final KarmaServer server;
        try {
          server = new KarmaServer(myProject, serverSettings);
          myServers.put(serverSettings, server);
          myServerByConfigFile.put(serverSettings.getConfigurationFilePath(), server);
        }
        finally {
          myStartingServers.remove(serverSettings);
        }
        server.onTerminated(new KarmaServerTerminatedListener() {
          @Override
          public void onTerminated(int exitCode) {
            myServers.remove(serverSettings, server);
            myServerByConfigFile.remove(serverSettings.getConfigurationFilePath(), server);
          }
        });
        UIUtil.invokeLaterIfNeeded(() -> consumer.consume(server));
      }
      catch (final Exception e) {
        UIUtil.invokeLaterIfNeeded(() -> consumer.consume(e));
      }
    });
  }

}
