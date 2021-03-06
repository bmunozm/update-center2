package org.jvnet.hudson.update_center;

import hudson.util.VersionNumber;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import static org.jvnet.hudson.update_center.HudsonWar.HUDSON_CUT_OFF;

/**
 * A collection of artifacts from which we build index.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class BaseMavenRepository implements MavenRepository {

    protected static final Properties IGNORE = new Properties();

    static {
        try {
            IGNORE.load(Plugin.class.getClassLoader().getResourceAsStream("artifact-ignores.properties"));
        } catch (IOException e) {
            throw new Error(e);
        }
    }
    public Collection<PluginHistory> listHudsonPlugins() throws IOException {

        Map<String, PluginHistory> plugins =
                new TreeMap<String, PluginHistory>(String.CASE_INSENSITIVE_ORDER);

        Set<String> excluded = new HashSet<String>();
        final Collection<ArtifactCoordinates> results = listAllPlugins();
        ARTIFACTS: for (ArtifactCoordinates a : results) {
            if (a.version.contains("SNAPSHOT"))     continue;       // ignore snapshots
            if (a.version.contains("JENKINS"))      continue;       // non-public releases for addressing specific bug fixes
            // Don't add blacklisted artifacts
            if (IGNORE.containsKey(a.artifactId)) {
                if (excluded.add(a.artifactId)) {
                    System.out.println("=> Ignoring " + a.artifactId + " because this artifact is blacklisted");
                }
                continue;
            }
            if (IGNORE.containsKey(a.artifactId + "-" + a.version)) {
                System.out.println("=> Ignoring " + a.artifactId + ", version " + a.version + " because this version is blacklisted");
                continue;
            }

            PluginHistory p = plugins.get(a.artifactId);
            if (p==null) {
                p=new PluginHistory(a.artifactId);
                plugins.put(a.artifactId, p);
            }
            HPI hpi = createHpiArtifact(a, p);

            for (PluginFilter pluginFilter : pluginFilters) {
                if (pluginFilter.shouldIgnore(hpi)) {
                    continue ARTIFACTS;
                }
            }

            p.addArtifact(hpi);
            p.groupId.add(a.groupId);
        }
        return plugins.values();
    }

    /**
     * Adds a plugin filter.
     * @param filter Filter to be added.
     */
    public void addPluginFilter(@Nonnull PluginFilter filter) {
        pluginFilters.add(filter);
    }

    public void resetPluginFilters() {
        this.pluginFilters.clear();
    }

    private List<PluginFilter> pluginFilters = new ArrayList<>();

    /**
     * Discover all hudson.war versions. Map must be sorted by version number, descending.
     */
    public TreeMap<VersionNumber,HudsonWar> getHudsonWar() throws IOException {
        TreeMap<VersionNumber,HudsonWar> r = new TreeMap<VersionNumber, HudsonWar>(VersionNumber.DESCENDING);
        listWar(r, "org.jenkins-ci.main", null);
        listWar(r, "org.jvnet.hudson.main", HUDSON_CUT_OFF);
        return r;
    }

    @Override
    public HPI createHpiArtifact(ArtifactCoordinates a, PluginHistory p) {
        return new HPI(this,p,a);
    }

    protected abstract Set<ArtifactCoordinates> listAllJenkinsWars(String groupId) throws IOException;

    public HudsonWar createHudsonWarArtifact(ArtifactCoordinates a) {
        return new HudsonWar(this,a);
    }

    @Override
    public void listWar(TreeMap<VersionNumber, HudsonWar> r, String groupId, VersionNumber cap) throws IOException {
        final Set<ArtifactCoordinates> results = listAllJenkinsWars(groupId);
        for (ArtifactCoordinates a : results) {
            if (a.version.contains("SNAPSHOT"))     continue;       // ignore snapshots
            if (a.version.contains("JENKINS"))      continue;       // non-public releases for addressing specific bug fixes
            if (!a.artifactId.equals("jenkins-war")
                    && !a.artifactId.equals("hudson-war"))  continue;      // somehow using this as a query results in 0 hits.
            if (a.classifier!=null)  continue;          // just pick up the main war
            if (IGNORE.containsKey(a.artifactId + "-" + a.version)) {
                System.out.println("=> Ignoring " + a.artifactId + ", version " + a.version + " because this version is blacklisted");
                continue;
            }
            if (cap!=null && new VersionNumber(a.version).compareTo(cap)>0) continue;

            VersionNumber v = new VersionNumber(a.version);
            r.put(v, createHudsonWarArtifact(a));
        }
    }

    static final ArtifactRepositoryPolicy POLICY = new ArtifactRepositoryPolicy(true, "daily", "warn");

    /**
     * find the HPI for the specified plugin
     * @return the found HPI or null
     */
    public HPI findPlugin(String groupId, String artifactId, String version) throws IOException {
        Collection<PluginHistory> all = listHudsonPlugins();

        for (PluginHistory p : all) {
            for (HPI h : p.artifacts.values()) {
                if (h.isEqualsTo(groupId, artifactId, version))
                  return h;
            }
        }
        return null;
    }
}
