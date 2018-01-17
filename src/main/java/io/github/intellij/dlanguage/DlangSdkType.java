package io.github.intellij.dlanguage;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.projectRoots.*;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import io.github.intellij.dlanguage.icons.DlangIcons;
import io.github.intellij.dlanguage.library.LibFileRootType;
import java.io.IOException;
import java.nio.file.Files;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Responsible for the mechanics when pressing "+" in the SDK configuration, as well as the project
 * SDK configuration.
 */
public class DlangSdkType extends SdkType {

    private static final Logger LOG = Logger.getInstance(DlangSdkType.class);

    private static final String SDK_TYPE_ID = "DMD2 SDK";
    private static final String SDK_NAME = "DMD v2 SDK";

    @Nullable
    private static File DEFAULT_DMD_PATH = null;
    @Nullable
    private static File DEFAULT_DOCUMENTATION_PATH = null;
    @Nullable
    private static File DEFAULT_PHOBOS_PATH = null;
    @Nullable
    private static File DEFAULT_DRUNTIME_PATH = null;

    static {
        if (SystemInfo.isWindows) {
            DEFAULT_DMD_PATH = new File("C:/D/dmd2/windows/bin/dmd.exe");
            DEFAULT_DOCUMENTATION_PATH = new File("C:/D/dmd2/html/d");
            DEFAULT_PHOBOS_PATH = new File("C:/D/dmd2/src/phobos");
            DEFAULT_DRUNTIME_PATH = new File("C:/D/dmd2/src/druntime/import");
        } else if (SystemInfo.isMac) {
            DEFAULT_DMD_PATH = new File(
                "/usr/local/opt/dmd"); // correct for Homebrew, standard maybe '/usr/local/bin'
            //DEFAULT_DOCUMENTATION_PATH = new File("");
            DEFAULT_PHOBOS_PATH = new File("/Library/D/dmd/src/phobos");
            DEFAULT_DRUNTIME_PATH = new File("/Library/D/dmd/src/druntime/import");
        } else if (SystemInfo.isUnix) {
            DEFAULT_DMD_PATH = new File("/usr/bin/dmd");
            DEFAULT_DOCUMENTATION_PATH = new File("/usr/share/dmd/html/d");
            DEFAULT_PHOBOS_PATH = new File("/usr/include/dmd/phobos");
            DEFAULT_DRUNTIME_PATH = new File("/usr/include/dmd/druntime/import");
        } else {
            LOG.warn(String.format("We didn't cater for %s", SystemInfo.getOsNameAndVersion()));
        }
    }

    private File dmdBinary = null;

    @NotNull
    public static DlangSdkType getInstance() {
        return SdkType.findInstance(DlangSdkType.class);
    }

    public DlangSdkType() {
        super(SDK_TYPE_ID);
    }

    @NotNull
    @Override
    public Icon getIconForAddAction() {
        return DlangIcons.FILE;
    }

    @NotNull
    @Override
    public Icon getIcon() {
        return DlangIcons.FILE;
    }

    @Nullable
    @Override
    public String suggestHomePath() {
        return DEFAULT_DMD_PATH != null && DEFAULT_DMD_PATH.exists() ? DEFAULT_DMD_PATH
            .getAbsolutePath() : null;
    }

    /* When user set up DMD SDK path this method checks if specified path contains DMD compiler executable. */
    @Override
    public boolean isValidSdkHome(final String sdkHome) {
        final String executableName = SystemInfo.isWindows ? "dmd.exe" : "dmd";

        File dmdBinary = new File(sdkHome, executableName);

        if (dmdBinary.exists() && dmdBinary.canExecute()) {
            this.dmdBinary = dmdBinary;
            return true;
        }

        if (SystemInfo.isWindows) {
            final File dmdHome = new File(sdkHome);
            if (dmdHome.exists() && dmdHome.isDirectory()) {
                dmdBinary = Paths.get(sdkHome, "windows", "bin", executableName)
                    .toFile(); // C:\D\dmd2\windows\bin\dmd.exe
            }
        }

        if (dmdBinary.exists() && dmdBinary.canExecute()) {
            this.dmdBinary = dmdBinary;
            return true;
        }
        return false;
    }

    @Override
    public String suggestSdkName(final String currentSdkName, final String sdkHome) {
        final String version = getDmdVersion(sdkHome);
        return version != null ? version : SDK_NAME;
    }

    class SetupStatus{
        private boolean runtime;
        private boolean phobos;
        private boolean documentation;

        SetupStatus(final boolean runtime, final boolean phobos, final boolean documentation) {
            this.runtime = runtime;
            this.phobos = phobos;
            this.documentation = documentation;
        }

        boolean getRuntimeStatus() {
            return runtime;
        }

        boolean getPhobosStatus() {
            return phobos;
        }

        boolean getDocumentationStatus() {
            return documentation;
        }

        public void setRuntime(final boolean runtime) {
            this.runtime = runtime;
        }

        public void setPhobos(final boolean phobos) {
            this.phobos = phobos;
        }

        public void setDocumentation(final boolean documentation) {
            this.documentation = documentation;
        }
    }

    /**
     * Windows has docs in 'C:\D\dmd2\html\d' and sources in ['C:\D\dmd2\src\phobos',
     * 'C:\D\dmd2\src\druntime\import'] OSX has docs in ??? and sources in
     * ['/Library/D/dmd/src/phobos', '/Library/D/dmd/src/druntime/import'] Linux has docs in
     * '/usr/share/dmd/html/d' and sources in ['/usr/include/dmd/phobos',
     * '/usr/include/dmd/druntime/import']
     *
     * @param sdk The DMD installation
     */
    @Override
    public void setupSdkPaths(@NotNull final Sdk sdk) {
        final SdkModificator sdkModificator = sdk.getSdkModificator();

        SetupStatus status = new SetupStatus(false,false,false);

        if (SystemInfo.isWindows) {
            status = setupSDKPathsFromWindowsConfigFile(sdk,sdkModificator);
        }

        // documentation paths (todo: find out why using 'OrderRootType.DOCUMENTATION' didn't work)
        if (!status.getDocumentationStatus()) {
            setupDocumentationPath(sdk, sdkModificator, status);
        }

        // add phobos to sources root
        if (!status.getPhobosStatus()) {
            setupPhobosPaths(sdkModificator, status);
        }

        // add druntime to sources root
        if (!status.getRuntimeStatus()) {
            setupRuntimePaths(sdkModificator, status);
        }

        sdkModificator.commitChanges();
    }

    private void setupRuntimePaths(final SdkModificator sdkModificator, final SetupStatus status) {
        if (DEFAULT_DRUNTIME_PATH != null) {
            final VirtualFile druntimeSource =
                DEFAULT_DRUNTIME_PATH.isDirectory() ? LocalFileSystem.getInstance()
                    .findFileByPath(DEFAULT_DRUNTIME_PATH.getAbsolutePath()) : null;
            if (druntimeSource != null) {
                sdkModificator.addRoot(druntimeSource, OrderRootType.SOURCES);
            }else{
                status.setRuntime(false);
                return;
            }
        }
        status.setRuntime(true);
    }

    private void setupPhobosPaths(final SdkModificator sdkModificator, final SetupStatus status) {
        if (DEFAULT_PHOBOS_PATH != null) {
            final VirtualFile phobosSource =
                DEFAULT_PHOBOS_PATH.isDirectory() ? LocalFileSystem.getInstance()
                    .findFileByPath(DEFAULT_PHOBOS_PATH.getAbsolutePath()) : null;
            if (phobosSource != null) {
                sdkModificator.addRoot(phobosSource, OrderRootType.SOURCES);
            }else{
                status.setPhobos(false);
                return;
            }
        }
        status.setPhobos(true);
    }

    private void setupDocumentationPath(@NotNull final Sdk sdk, final SdkModificator sdkModificator,
        final SetupStatus status) {
//        final File docDir = DEFAULT_DOCUMENTATION_PATH;//Paths.get(getDmdPath(sdk), "html", "d").toFile();
//        final VirtualFile docs = docDir != null && docDir.isDirectory() ? LocalFileSystem.getInstance().findFileByPath(docDir.getAbsolutePath()) : null;
//        if (docs != null) {
//            sdkModificator.addRoot(docs, OrderRootType.DOCUMENTATION);
//        } else {
//            final VirtualFile fxDocUrl = VirtualFileManager.getInstance().findFileByUrl("http://dlang.org/spec/spec.html");
//            if(fxDocUrl == null){
//                status.setDocumentation(false);
//                return;
//            }
//            sdkModificator.addRoot(fxDocUrl, OrderRootType.DOCUMENTATION);
//        }
        status.setDocumentation(true);//todo adding documentation doesn't work but its not clear why

    }

    @NotNull
    private SetupStatus setupSDKPathsFromWindowsConfigFile(final Sdk sdk,
        final SdkModificator sdkModificator) {
        final GeneralCommandLine commandLine = new GeneralCommandLine(getDmdPath(sdk));
        try {
            //this array works around some of the limitations of java
            final String[] configFileArray = {null};
            final OSProcessHandler handler = new OSProcessHandler(commandLine);
            handler.addProcessListener(new ProcessAdapter() {
                @Override
                public void onTextAvailable(@NotNull final ProcessEvent event,
                    @NotNull final Key outputType) {
                    if (event.getText().contains("Config file: ")) {
                        configFileArray[0] = event.getText().replace("Config file:", "");
                        configFileArray[0] = configFileArray[0].trim();
                    }
                }
            });
            handler.startNotify();
            handler.waitFor();
            final String configFile = configFileArray[0];
            if (configFile == null) {
                return new SetupStatus(false,false,false);
            }
            final File file = new File(configFile);
            if (!file.exists()) {
                return new SetupStatus(false,false,false);
            }
            //DFLAGS="-I%@P%\..\..\src\phobos" "-I%@P%\..\..\src\druntime\import"
            final String[] phobos = new String[1];
            final Pattern phobosPattern = Pattern
                .compile("\"-I%@P%([\\.\\\\A-Za-z]+phobos[\\.\\\\A-Za-z]*)\"");
            final String[] druntime = new String[1];
            final Pattern druntimePattern = Pattern
                .compile("\"-I%@P%([\\.\\\\A-Za-z]+druntime\\\\import[\\.\\\\A-Za-z]*)\"");
            ;
            Files.lines(file.toPath()).forEach(line -> {
                if (line.contains("DFLAGS=")) {
                    final Matcher phobosMatcher = phobosPattern.matcher(line);
                    final Matcher druntimeMatcher = druntimePattern.matcher(line);
                    if(phobosMatcher.find()){
                        phobos[0] = phobosMatcher.group(1);
                    }
                    if(druntimeMatcher.find()){
                        druntime[0] = druntimeMatcher.group(1);
                    }
                }
            });
            final String phobosPath = (new File(getDmdPath(sdk))).getParent() + phobos[0];
            final String druntimePath = (new File(getDmdPath(sdk))).getParent() + druntime[0];
            final File phobosFile = new File(phobosPath);
            final File druntimeFile = new File(druntimePath);
            if (phobosFile.exists() && druntimeFile.exists()) {
                final VirtualFile phobosVirtualFile = LocalFileSystem.getInstance().findFileByPath(phobosFile.getAbsolutePath());
                final VirtualFile druntimeVirtualFile = LocalFileSystem.getInstance().findFileByPath(druntimeFile.getAbsolutePath());
                if(phobosVirtualFile == null || druntimeVirtualFile == null) return new SetupStatus(false, false,false);
                sdkModificator.addRoot(phobosVirtualFile, OrderRootType.SOURCES);
                sdkModificator.addRoot(druntimeVirtualFile, OrderRootType.SOURCES);
                return new SetupStatus(true,true,false);
            } else {
                return new SetupStatus(false, false,false);
            }

        } catch (final ExecutionException | IOException e) {
            Logger.getInstance(DlangSdkType.class).error(e);
            return new SetupStatus(false, false,false);
        }
    }

    @Nullable
    @Override // takes precedence over getVersionString(String)
    public String getVersionString(@NotNull final Sdk sdk) {
        final String sdkName = sdk.getName();
        return StringUtil.isNotEmpty(sdkName) ? sdkName.substring(sdkName.indexOf('v') + 1) : null;
    }

    @Nullable
    @Override
    public String getVersionString(@NotNull final String sdkHome) {
        final String version = getDmdVersion(sdkHome);

        if (StringUtil.isNotEmpty(version)) {
            final Matcher m = Pattern.compile("(?:.*v)(.+)").matcher(version);
            return m.matches() ? m.group(1) : null;
        }

        return null;
    }

    @Nullable
    @Override
    public AdditionalDataConfigurable createAdditionalDataConfigurable(@NotNull final SdkModel sdkModel,
        @NotNull final SdkModificator sdkModificator) {
        return null;
    }

    @NotNull
    @Override
    public String getPresentableName() {
        return DlangBundle.INSTANCE.message("compilers.dmd.presentableName");
    }

    @Override
    public void saveAdditionalData(@NotNull final SdkAdditionalData sdkAdditionalData,
        @NotNull final Element element) {
        //pass
    }

    @Override
    public boolean isRootTypeApplicable(@NotNull final OrderRootType type) {
        return type != LibFileRootType.getInstance() && super.isRootTypeApplicable(type);
    }

    /**
     * Try to execute 'dmd --version' and return first line of the output.
     *
     * @param sdkHome path to dmd home directory
     * @return String containing DMD version or null
     */
    @Nullable
    private String getDmdVersion(final String sdkHome) {
        if (isValidSdkHome(sdkHome)) {
            final GeneralCommandLine cmd = new GeneralCommandLine();
            //cmd.withWorkDirectory(sdkHome.getAbsolutePath());
            cmd.setExePath(dmdBinary.getAbsolutePath());
            cmd.addParameter("--version");

            try {
                final ProcessOutput output = new CapturingProcessHandler(
                    cmd.createProcess(),
                    Charset.defaultCharset(),
                    cmd.getCommandLineString()
                ).runProcess();

                //Parse output of a DMD compiler
                final List<String> outputLines = output.getStdoutLines();
                if (!outputLines.isEmpty()) {
                    final String version = outputLines.get(0).trim();
                    LOG.debug(String.format("Found version: %s", version));
                    return version;
                }
            } catch (final ExecutionException e) {
                LOG.error("There was a problem running 'dmd --version'", e);
            }
        }
        return null;
    }

    /* Returns full path to DMD compiler executable */
    public String getDmdPath(@NotNull final Sdk sdk) {
        final String homePath = sdk.getHomePath();

        if (isValidSdkHome(homePath)) {
            return dmdBinary.getAbsolutePath();
        }

        return "dmd"; // it may just be on the PATH
    }

    /* Returns full path to DMD compiler sources */
//    public static String getDmdSourcesPaths(final Sdk sdk) {
//        final String sdkHome = sdk.getHomePath();
//        final String executableName = SystemInfo.isWindows ? "dmd.exe" : "dmd";
//        final File dmdCompilerFile = new File(sdkHome, executableName);
//        return dmdCompilerFile.getAbsolutePath();
//    }
}


