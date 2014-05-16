package com.siberika.idea.pascal.sdk;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.projectRoots.AdditionalDataConfigurable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkAdditionalData;
import com.intellij.openapi.projectRoots.SdkModel;
import com.intellij.openapi.projectRoots.SdkModificator;
import com.intellij.openapi.projectRoots.SdkType;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.siberika.idea.pascal.PascalIcons;
import com.siberika.idea.pascal.jps.model.JpsPascalModelSerializerExtension;
import com.siberika.idea.pascal.jps.sdk.PascalSdkData;
import com.siberika.idea.pascal.jps.sdk.PascalSdkUtil;
import com.siberika.idea.pascal.jps.util.FileUtil;
import com.siberika.idea.pascal.util.SysUtils;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

/**
 * Author: George Bakhtadze
 * Date: 10/01/2013
 */
public class FPCSdkType extends BasePascalSdkType {

    @NotNull
    public static FPCSdkType getInstance() {
        return SdkType.findInstance(FPCSdkType.class);
    }

    public static Sdk findSdk(Module module) {
        if (module == null) {
            return null;
        }

        Sdk sdk = ModuleRootManager.getInstance(module).getSdk();
        if (sdk != null && (sdk.getSdkType().equals(FPCSdkType.getInstance()))) {
            return sdk;
        }

        return null;
    }

    public FPCSdkType() {
        super(JpsPascalModelSerializerExtension.PASCAL_SDK_TYPE_ID);
        InputStream definesStream = getClass().getClassLoader().getResourceAsStream("/defines.xml");
        if (definesStream != null) {
            DefinesParser.parse(definesStream);
        }
        InputStream builtinsUrl = getClass().getClassLoader().getResourceAsStream("/builtins.xml");
        if (builtinsUrl != null) {
            BuiltinsParser.parse(builtinsUrl);
        }
    }

    @NotNull
    @Override
    public Icon getIcon() {
        return PascalIcons.GENERAL;
    }

    @NotNull
    @Override
    public Icon getIconForAddAction() {
        return getIcon();
    }

    public static final List<String> DEFAULT_SDK_LOCATIONS = Arrays.asList("/usr/lib/codetyphon/fpc", "/usr/lib/fpc", "/usr/share/fpc");

    @Nullable
    @Override
    public String suggestHomePath() {
        if (SystemInfo.isWindows) {
            return "C:\\fpc\\bin";
        } else if (SystemInfo.isLinux) {
            for (String path : DEFAULT_SDK_LOCATIONS) {
                if (FileUtil.exists(path)) {
                    return path;
                }
            }
        }
        return null;
    }

    @Override
    public boolean isValidSdkHome(@NotNull final String path) {
        final File fpcExe = PascalSdkUtil.getCompilerExecutable(path);
        return fpcExe.isFile() && fpcExe.canExecute();
    }

    @NotNull
    public String suggestSdkName(@Nullable final String currentSdkName, @NotNull final String sdkHome) {
        String version = getVersionString(sdkHome);
        if (version == null) return "Free Pascal v. ?? at " + sdkHome;
        return "Free Pascal v. " + version;
    }

    @Nullable
    public String getVersionString(String sdkHome) {
        return SysUtils.runAndGetStdOut(sdkHome, PascalSdkUtil.getCompilerExecutable(sdkHome).getAbsolutePath(), PascalSdkUtil.FPC_PARAMS_VERSION_GET);
    }

    @Override
    public void saveAdditionalData(@NotNull final SdkAdditionalData additionalData, @NotNull final Element additional) {
        if (additionalData instanceof PascalSdkData) {
            Object val = ((PascalSdkData) additionalData).getValue(PascalSdkData.DATA_KEY_COMPILER_OPTIONS);
            additional.setAttribute(PascalSdkData.DATA_KEY_COMPILER_OPTIONS, val != null ? (String) val : "");
        }
    }

    @Nullable
    @Override
    public SdkAdditionalData loadAdditionalData(Element additional) {
        PascalSdkData result = new PascalSdkData();
        if (additional != null) {
            result.setValue(PascalSdkData.DATA_KEY_COMPILER_OPTIONS, additional.getAttributeValue(PascalSdkData.DATA_KEY_COMPILER_OPTIONS));
        }
        return result;
    }

    @NonNls
    @Override
    public String getPresentableName() {
        return "Free Pascal SDK";
    }

    @Override
    public void setupSdkPaths(@NotNull final Sdk sdk) {
        configureSdkPaths(sdk);
    }

    private static void configureSdkPaths(@NotNull final Sdk sdk) {
        final SdkModificator[] sdkModificatorHolder = new SdkModificator[]{null};
        final SdkModificator sdkModificator = sdk.getSdkModificator();
        File rtlDir = new File(sdk.getHomePath() + File.separatorChar + "units" + File.separatorChar + PascalSdkUtil.target + File.separatorChar + "rtl");
        final VirtualFile dir = LocalFileSystem.getInstance().findFileByIoFile(rtlDir);
        sdkModificator.addRoot(dir, OrderRootType.SOURCES);

        sdkModificatorHolder[0] = sdkModificator;

        if (sdkModificatorHolder[0] != null) {
            sdkModificatorHolder[0].commitChanges();
        }
    }

    @Override
    public boolean isRootTypeApplicable(OrderRootType type) {
        return type.equals(OrderRootType.SOURCES);
    }

    @Nullable
    @Override
    public AdditionalDataConfigurable createAdditionalDataConfigurable(@NotNull final SdkModel sdkModel, @NotNull final SdkModificator sdkModificator) {
        return new PascalSdkConfigUI();
    }

}
