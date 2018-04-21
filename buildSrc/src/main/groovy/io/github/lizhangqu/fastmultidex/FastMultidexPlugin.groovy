package io.github.lizhangqu.fastmultidex

import com.android.build.gradle.AppExtension
import com.android.build.gradle.internal.core.GradleVariantConfiguration
import com.android.build.gradle.internal.pipeline.TransformTask
import com.android.build.gradle.internal.transforms.DexTransform
import com.android.build.gradle.internal.variant.ApplicationVariantData
import com.android.builder.core.AndroidBuilder
import com.android.builder.core.DefaultDexOptions
import com.android.builder.core.VariantConfiguration
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskCollection

class FastMultidexPlugin implements Plugin<Project> {

    static String getAndroidGradlePluginVersionCompat() {
        String version = null
        try {
            Class versionModel = Class.forName("com.android.builder.model.Version")
            def versionFiled = versionModel.getDeclaredField("ANDROID_GRADLE_PLUGIN_VERSION")
            versionFiled.setAccessible(true)
            version = versionFiled.get(null)
        } catch (Exception e) {
            version = "unknown"
        }
        return version
    }

    static List<TransformTask> findTransformTaskByTransformType(Project project, VariantConfiguration variantConfiguration, Class<?> transformClass) {
        List<TransformTask> transformTasksList = new ArrayList<>()
        TaskCollection<TransformTask> transformTasks = project.getTasks().withType(TransformTask.class)
        SortedMap<String, TransformTask> transformTaskSortedMap = transformTasks.getAsMap()
        String variantName = variantConfiguration.getFullName()
        transformTaskSortedMap.each { String taskName, TransformTask transformTask ->
            if (variantName == transformTask.getVariantName()) {
                if (transformTask.getTransform().getClass() == transformClass) {
                    transformTasksList.add(transformTask)
                }
            }
        }
        return transformTasksList
    }

    @Override
    void apply(Project project) {
        String androidGradlePluginVersionCompat = getAndroidGradlePluginVersionCompat()
        if (androidGradlePluginVersionCompat.startsWith("2.")) {
            project.afterEvaluate {
                AppExtension appExtension = project.getExtensions().findByType(AppExtension.class)
                appExtension.applicationVariants.all { def variant ->
                    ApplicationVariantData applicationVariantData = variant.getMetaClass().getProperty(variant, 'variantData')
                    GradleVariantConfiguration variantConfiguration = applicationVariantData.getVariantConfiguration()
                    List<TransformTask> transformTaskList = findTransformTaskByTransformType(project, variantConfiguration, DexTransform.class)
                    transformTaskList.each { TransformTask transformTask ->
                        DexTransform dexTransform = transformTask.transform
                        DefaultDexOptions dexOptions = dexTransform.getMetaClass().getProperty(dexTransform, 'dexOptions')
                        AndroidBuilder androidBuilder = dexTransform.getMetaClass().getProperty(dexTransform, 'androidBuilder')
                    }
                }
            }
        }

    }
}