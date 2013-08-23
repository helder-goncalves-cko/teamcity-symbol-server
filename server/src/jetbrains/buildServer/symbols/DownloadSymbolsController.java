package jetbrains.buildServer.symbols;

import jetbrains.buildServer.controllers.AuthorizationInterceptor;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.serverSide.artifacts.BuildArtifact;
import jetbrains.buildServer.serverSide.artifacts.BuildArtifactsViewMode;
import jetbrains.buildServer.serverSide.auth.Permission;
import jetbrains.buildServer.serverSide.metadata.BuildMetadataEntry;
import jetbrains.buildServer.serverSide.metadata.MetadataStorage;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.users.UserModel;
import jetbrains.buildServer.util.FileUtil;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import jetbrains.buildServer.web.util.SessionUser;
import jetbrains.buildServer.web.util.WebUtil;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;

/**
 * @author Evgeniy.Koshkin
 */
public class DownloadSymbolsController extends BaseController {

  private static final String APP_SYMBOLS = "/" + SymbolsConstants.APP_SYMBOLS;
  private static final String APP_SYMBOLS_INTERNAL = "/" + SymbolsConstants.APP_SYMBOLS_INTERNAL;

  private static final String COMPRESSED_FILE_EXTENSION = "pd_";
  private static final String FILE_POINTER_FILE_EXTENSION = "ptr";

  private static final Logger LOG = Logger.getLogger(DownloadSymbolsController.class);

  @NotNull
  private final UserModel myUserModel;
  @NotNull private final MetadataStorage myBuildMetadataStorage;

  public DownloadSymbolsController(@NotNull SBuildServer server,
                                   @NotNull WebControllerManager controllerManager,
                                   @NotNull AuthorizationInterceptor authInterceptor,
                                   @NotNull UserModel userModel,
                                   @NotNull MetadataStorage buildMetadataStorage) {
    super(server);
    myUserModel = userModel;
    myBuildMetadataStorage = buildMetadataStorage;
    final String path = APP_SYMBOLS + "**";
    controllerManager.registerController(path, this);
    authInterceptor.addPathNotRequiringAuth(path);
    final String internalPath = APP_SYMBOLS_INTERNAL + "**";
    controllerManager.registerController(internalPath, this);
  }

  @Nullable
  @Override
  protected ModelAndView doHandle(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response) throws Exception {
    final String requestURI = request.getRequestURI();

    if(requestURI.endsWith(APP_SYMBOLS)){
      response.sendError(HttpServletResponse.SC_OK, "TeamCity Symbol Server available");
      return null;
    }

    if(requestURI.endsWith(COMPRESSED_FILE_EXTENSION)){
      WebUtil.notFound(request, response, "File not found", null);
      return null;
    }
    if(requestURI.endsWith(FILE_POINTER_FILE_EXTENSION)){
      WebUtil.notFound(request, response, "File not found", null);
      return null;
    }

    final SUser user = SessionUser.getUser(request);
    if (user != null && !user.isPermissionGrantedGlobally(Permission.VIEW_BUILD_RUNTIME_DATA)) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN, "You have no permissions to download PDB files.");
      return null;
    } else {
      if (!myServer.getLoginConfiguration().isGuestLoginAllowed() || !myUserModel.getGuestUser().isPermissionGrantedGlobally(Permission.VIEW_BUILD_RUNTIME_DATA)) {

        String authRequiredUrl;
        final String contextPath = request.getContextPath();
        if(requestURI.startsWith(contextPath))
          authRequiredUrl = WebUtil.HTTP_AUTH_PREFIX + requestURI.substring(contextPath.length() + 1);
        else
          authRequiredUrl = WebUtil.HTTP_AUTH_PREFIX + requestURI.substring(1);

        authRequiredUrl = authRequiredUrl.replace(APP_SYMBOLS, APP_SYMBOLS_INTERNAL);

        LOG.debug("Unauthorized access to PDB files is denied. Forwarding request to auth-required URL " + authRequiredUrl);
        final RequestDispatcher dispatcher = request.getRequestDispatcher(authRequiredUrl);
        dispatcher.forward(request, response);
        return null;
      }
    }

    final String valuableUriPart = requestURI.substring(requestURI.indexOf(APP_SYMBOLS) + APP_SYMBOLS.length());
    final int firstDelimiterPosition = valuableUriPart.indexOf('/');
    final String fileName = valuableUriPart.substring(0, firstDelimiterPosition);
    final String signature = valuableUriPart.substring(firstDelimiterPosition + 1, valuableUriPart.indexOf('/', firstDelimiterPosition + 1));
    final String guid = signature.substring(0, signature.length() - 1); //last symbol is PEDebugType
    LOG.debug(String.format("Symbol file requested. File name: %s. Guid: %s.", fileName, guid));

    final BuildArtifact buildArtifact = findArtifact(guid, fileName);
    if(buildArtifact == null){
      WebUtil.notFound(request, response, "Symbol file not found", null);
      LOG.debug(String.format("Symbol file not found. File name: %s. Guid: %s.", fileName, guid));
      return null;
    }

    BufferedOutputStream output = new BufferedOutputStream(response.getOutputStream());
    try {
      InputStream input = buildArtifact.getInputStream();
      try {
        FileUtil.copyStreams(input, output);
      } finally {
        FileUtil.close(input);
      }
    } finally {
      FileUtil.close(output);
    }

    return null;
  }

  private BuildArtifact findArtifact(String guid, String fileName) {
    final Iterator<BuildMetadataEntry> entryIterator = myBuildMetadataStorage.getEntriesByKey(BuildSymbolsIndexProvider.PROVIDER_ID, guid);
    if(!entryIterator.hasNext()){
      LOG.debug(String.format("No items found in symbol index for guid '%s'", guid));
      return null;
    }
    final BuildMetadataEntry entry = entryIterator.next();
    final Map<String,String> metadata = entry.getMetadata();
    final String storedFileName = metadata.get(BuildSymbolsIndexProvider.FILE_NAME_KEY);
    final String artifactPath = metadata.get(BuildSymbolsIndexProvider.ARTIFACT_PATH_KEY);
    if(!storedFileName.equals(fileName)){
      LOG.debug(String.format("File name '%s' stored for guid '%s' differs from requested '%s'.", storedFileName, guid, fileName));
      return null;
    }
    final long buildId = entry.getBuildId();
    final SBuild build = myServer.findBuildInstanceById(buildId);
    if(build == null){
      LOG.debug(String.format("Build not found by id %d.", buildId));
      return null;
    }
    final BuildArtifact buildArtifact = build.getArtifacts(BuildArtifactsViewMode.VIEW_DEFAULT_WITH_ARCHIVES_CONTENT).getArtifact(artifactPath);
    if(buildArtifact == null){
      LOG.debug(String.format("Artifact not found by path %s for build with id %d.", artifactPath, buildId));
    }
    return buildArtifact;
  }
}