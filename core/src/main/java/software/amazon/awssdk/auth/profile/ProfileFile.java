/*
 * Copyright 2010-2017 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.auth.profile;

import static software.amazon.awssdk.utils.FunctionalUtils.invokeSafely;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.auth.profile.internal.ProfileFileLocations;
import software.amazon.awssdk.auth.profile.internal.ProfileFileReader;
import software.amazon.awssdk.core.AwsSystemSetting;
import software.amazon.awssdk.core.auth.ProfileCredentialsProvider;
import software.amazon.awssdk.utils.IoUtils;
import software.amazon.awssdk.utils.Validate;
import software.amazon.awssdk.utils.builder.SdkBuilder;

/**
 * Provides programmatic access to the contents of an AWS configuration profile file.
 *
 * AWS configuration profiles allow you to share multiple sets of AWS security credentials between different tools such as the
 * AWS SDK for Java and the AWS CLI.
 *
 * <p>
 * For more information on setting up AWS configuration profiles, see:
 * http://docs.aws.amazon.com/cli/latest/userguide/cli-chap-getting-started.html
 *
 * <p>
 * A profile file can be created with {@link #builder()} and merged with other profiles files with {@link #aggregator()}. By
 * default, the SDK will use the {@link #defaultProfileFile()} when that behavior hasn't been explicitly overridden.
 */
@SdkPublicApi
public class ProfileFile {
    private final Map<String, Profile> profiles;

    /**
     * @see #builder()
     */
    @SdkInternalApi
    ProfileFile(Map<String, Map<String, String>> rawProfiles) {
        Validate.paramNotNull(rawProfiles, "rawProfiles");
        this.profiles = convertRawProfiles(rawProfiles);
    }

    /**
     * Create a builder for a {@link ProfileFile}.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a builder that can merge multiple {@link ProfileFile}s together.
     */
    public static Aggregator aggregator() {
        return new Aggregator();
    }

    /**
     * Get the default profile file, using the credentials file from "~/.aws/credentials", the config file from "~/.aws/config"
     * and the "default" profile. This default behavior can be customized using the
     * {@link AwsSystemSetting#AWS_SHARED_CREDENTIALS_FILE}, {@link AwsSystemSetting#AWS_CONFIG_FILE} and
     * {@link AwsSystemSetting#AWS_PROFILE} settings or by specifying a different profile file and profile name when creating a
     * {@link ProfileCredentialsProvider}.
     */
    public static ProfileFile defaultProfileFile() {
        return ProfileFile.aggregator()
                          .apply(ProfileFile::addCredentialsFile)
                          .apply(ProfileFile::addConfigFile)
                          .build();
    }

    /**
     * Retrieve the profile from this file with the given name.
     *
     * @param profileName The name of the profile that should be retrieved from this file.
     * @return The profile, if available.
     */
    public final Optional<Profile> profile(String profileName) {
        return Optional.ofNullable(profiles.get(profileName));
    }

    /**
     * Retrieve an unmodifiable collection including all of the profiles in this file.
     * @return An unmodifiable collection of the profiles in this file, keyed by profile name.
     */
    public final Map<String, Profile> profiles() {
        return profiles;
    }

    @Override
    public String toString() {
        return "ProfileFile(" + profiles.values() + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ProfileFile that = (ProfileFile) o;
        return Objects.equals(profiles, that.profiles);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profiles);
    }

    private static void addCredentialsFile(ProfileFile.Aggregator builder) {
        ProfileFileLocations.credentialsFileLocation()
                            .ifPresent(l -> builder.addFile(ProfileFile.builder()
                                                                       .content(l)
                                                                       .type(ProfileFile.Type.CREDENTIALS)
                                                                       .build()));
    }

    private static void addConfigFile(ProfileFile.Aggregator builder) {
        ProfileFileLocations.configurationFileLocation()
                            .ifPresent(l -> builder.addFile(ProfileFile.builder()
                                                                       .content(l)
                                                                       .type(ProfileFile.Type.CONFIGURATION)
                                                                       .build()));
    }

    /**
     * Convert the raw profile contents into {@link Profile} objects.
     */
    private Map<String, Profile> convertRawProfiles(Map<String, Map<String, String>> rawProfiles) {
        Map<String, Map<String, String>> sortedProfiles = sortProfilesWithParentsFirst(rawProfiles);
        Map<String, Profile> result = convertToProfilesMap(sortedProfiles);

        return Collections.unmodifiableMap(result);
    }

    /**
     * Return a linked hash map that guarantees a profile is always encountered after its parent when iterating over the entry
     * set. This is useful because a child profile needs access to its parent profile when loading credentials, so we want to
     * initialize the parent profiles first.
     */
    private Map<String, Map<String, String>> sortProfilesWithParentsFirst(Map<String, Map<String, String>> unsortedProfiles) {
        Map<String, Map<String, String>> sortedProfiles = new LinkedHashMap<>();

        while (!unsortedProfiles.isEmpty()) {
            String nextProfileToSort = unsortedProfiles.keySet().iterator().next();
            sortProfileAndParents(unsortedProfiles, sortedProfiles, nextProfileToSort, new HashSet<>());
        }

        return sortedProfiles;
    }

    /**
     * Remove the requested profile and its parents from the unsorted list and put them in the sorted list in an order such
     * that the parents occur earlier than the children.
     *
     * @param unsorted The unsorted collection of profiles so far.
     * @param sorted The sorted collection of profiles so far.
     * @param profileName The name of the profile to move to the sorted collection.
     * @param children Any known children of this profile, which will allow us to ensure there is no circular references in
     * the configuration file.
     */
    private void sortProfileAndParents(Map<String, Map<String, String>> unsorted,
                                       Map<String, Map<String, String>> sorted,
                                       String profileName,
                                       Set<String> children) {
        Validate.validState(!children.contains(profileName),
                            "Invalid profile file: Circular relationship detected with profiles %s.", children);

        Map<String, String> profileProperties = unsorted.get(profileName);
        Validate.validState(profileProperties != null, "Parent profile '%s' does not exist.", profileName);

        String parentProfileName = profileProperties.get(ProfileProperties.SOURCE_PROFILE);

        if (parentProfileName != null && !sorted.containsKey(parentProfileName)) {
            // The parent hasn't been moved over yet. Move it over before we move this one over.
            children.add(profileName);
            sortProfileAndParents(unsorted, sorted, parentProfileName, children);
            children.remove(profileName);
        }

        // The parents have all been moved to the sorted list, move this one over.
        sorted.put(profileName, profileProperties);
        unsorted.remove(profileName);
    }

    /**
     * Convert the sorted map of profile properties into a sorted list of profiles.
     */
    private Map<String, Profile> convertToProfilesMap(Map<String, Map<String, String>> sortedProfiles) {
        Map<String, Profile> result = new LinkedHashMap<>();
        for (Entry<String, Map<String, String>> rawProfile : sortedProfiles.entrySet()) {
            String parentProfileName = rawProfile.getValue().get(ProfileProperties.SOURCE_PROFILE);
            Profile parentProfile = result.get(parentProfileName);

            Profile profile = Profile.builder()
                                     .name(rawProfile.getKey())
                                     .properties(rawProfile.getValue())
                                     .credentialsParent(parentProfile)
                                     .build();

            result.put(profile.name(), profile);
        }

        return result;
    }

    /**
     * The supported types of profile files. The type of profile determines the way in which it is parsed.
     */
    public enum Type {
        /**
         * A configuration profile file, typically located at ~/.aws/config, that expects all profile names (except the default
         * profile) to be prefixed with "profile ". Any non-default profiles without this prefix will be ignored.
         */
        CONFIGURATION,

        /**
         * A credentials profile file, typically located at ~/.aws/credentials, that expects all profile name to have no
         * "profile " prefix. Any profiles with a profile prefix will be ignored.
         */
        CREDENTIALS
    }

    /**
     * A builder for a {@link ProfileFile}. {@link #content(Path)} (or {@link #content(InputStream)}) and {@link #type(Type)} are
     * required fields.
     */
    public static final class Builder implements SdkBuilder<Builder, ProfileFile> {
        private InputStream content;
        private Path contentLocation;
        private Type type;

        private Builder() {}

        /**
         * Configure the content of the profile file. This stream will be read from and then closed when {@link #build()} is
         * invoked.
         */
        public Builder content(InputStream contentStream) {
            this.contentLocation = null;
            this.content = contentStream;
            return this;
        }

        /**
         * Configure the location from which the profile file should be loaded.
         */
        public Builder content(Path contentLocation) {
            Validate.paramNotNull(contentLocation, "profileLocation");
            Validate.validState(Files.exists(contentLocation), "Profile file '%s' does not exist.", contentLocation);

            this.content = null;
            this.contentLocation = contentLocation;
            return this;
        }

        /**
         * Configure the {@link Type} of file that should be loaded.
         */
        public Builder type(Type type) {
            this.type = type;
            return this;
        }

        @Override
        public ProfileFile build() {
            InputStream stream = content != null ? content : invokeSafely(() -> Files.newInputStream(contentLocation));

            Validate.paramNotNull(type, "type");
            Validate.paramNotNull(stream, "content");

            try {
                return new ProfileFile(ProfileFileReader.parseFile(stream, type));
            } finally {
                IoUtils.closeQuietly(stream, null);
            }
        }
    }

    /**
     * A mechanism for merging multiple {@link ProfileFile}s together into a single file. This will merge their profiles and
     * properties together.
     */
    public static final class Aggregator implements SdkBuilder<Aggregator, ProfileFile> {
        private List<ProfileFile> files = new ArrayList<>();

        /**
         * Add a file to be aggregated. In the event that there is a duplicate profile/property pair in the files, files added
         * earliest to this aggregator will take precedence, dropping the duplicated properties in the later files.
         */
        public Aggregator addFile(ProfileFile file) {
            files.add(file);
            return this;
        }

        @Override
        public ProfileFile build() {
            Map<String, Map<String, String>> aggregateRawProfiles = new LinkedHashMap<>();
            for (int i = files.size() - 1; i >= 0; --i) {
                addToAggregate(aggregateRawProfiles, files.get(i));
            }
            return new ProfileFile(aggregateRawProfiles);
        }

        private void addToAggregate(Map<String, Map<String, String>> aggregateRawProfiles, ProfileFile file) {
            Map<String, Profile> profiles = file.profiles();
            for (Map.Entry<String, Profile> profile : profiles.entrySet()) {
                aggregateRawProfiles.compute(profile.getKey(), (k, current) -> {
                    if (current == null) {
                        return new HashMap<>(profile.getValue().properties());
                    } else {
                        current.putAll(profile.getValue().properties());
                        return current;
                    }
                });
            }
        }
    }
}