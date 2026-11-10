#include <dlfcn.h>
#include <stdio.h>

typedef int (*get_boolean_fn)(const char *, const char *, int *);

struct boolean_key {
    const char *group;
    const char *key;
};

int main(void) {
    static const struct boolean_key keys[] = {
        {"OemCommonCapbility", "isDisable4K30VideoEIS"},
        {"OemCommonCapbility", "disableDMAFenceForCTSorGSI"},
        {"OemCommonCapbility", "useMovieVideoLOGPipeline"},
        {"OemCommonCapbility", "PropertySetZoom"},
        {"OemCommonCapbility", "doesNotExistInTheFile"},
    };
    void *library = dlopen("/odm/lib64/libhwconfigurationutil.so", RTLD_NOW);
    if (library == NULL) {
        fprintf(stderr, "dlopen failed: %s\n", dlerror());
        return 2;
    }
    get_boolean_fn get_boolean = (get_boolean_fn)dlsym(
            library, "GetBooleanConfiguration");
    if (get_boolean == NULL) {
        fprintf(stderr, "dlsym failed: %s\n", dlerror());
        dlclose(library);
        return 3;
    }
    for (unsigned int index = 0; index < sizeof(keys) / sizeof(keys[0]); ++index) {
        int value = -1;
        int result = get_boolean(keys[index].group, keys[index].key, &value);
        printf("group=%s key=%s result=%d value=%d\n",
               keys[index].group, keys[index].key, result, value);
    }
    dlclose(library);
    return 0;
}
