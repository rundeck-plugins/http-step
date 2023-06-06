package edu.ohio.ais.rundeck.util;

import com.dtolabs.rundeck.core.execution.ExecutionContext;
import com.dtolabs.rundeck.core.execution.proxy.DefaultSecretBundle;
import com.dtolabs.rundeck.core.execution.proxy.SecretBundle;
import com.dtolabs.rundeck.core.storage.ResourceMeta;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SecretBundleUtil {

    public static List<String> getListSecrets(Map<String, Object> configuration) {
        List<String> listSecretPath = new ArrayList<>();
        String passwordPath = (String)configuration.get("password");

        if(passwordPath!=null && !passwordPath.isEmpty() ){
            listSecretPath.add(passwordPath);
        }

        return listSecretPath;
    }

    public static SecretBundle getSecrets(ExecutionContext context,  Map<String, Object> configuration){
        DefaultSecretBundle secretBundle = new DefaultSecretBundle();
        String passwordPath = (String)configuration.get("password");

        if(passwordPath!=null && !passwordPath.isEmpty() ){
            byte[] content = SecretBundleUtil.getStoragePassword(context,passwordPath);
            if(content!=null){
                secretBundle.addSecret(passwordPath, content);
            }
        }
        return secretBundle;
    }

    public static byte[] getStoragePassword(ExecutionContext context, String path){
        try(ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            ResourceMeta contents = context.getStorageTree().getResource(path).getContents();
            contents.writeContent(byteArrayOutputStream);
            return byteArrayOutputStream.toByteArray();
        } catch (Exception e) {
            context.getExecutionLogger().log(0, e.getMessage());
            return null;
        }
    }
}
