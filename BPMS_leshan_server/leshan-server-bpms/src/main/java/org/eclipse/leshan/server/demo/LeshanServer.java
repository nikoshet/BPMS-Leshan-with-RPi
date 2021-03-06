package org.eclipse.leshan.server.demo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.AlgorithmParameters;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.InvalidParameterSpecException;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.leshan.LwM2m;
import org.eclipse.leshan.core.model.LwM2mModel;
import org.eclipse.leshan.core.model.ObjectLoader;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeDecoder;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeEncoder;
import org.eclipse.leshan.core.node.codec.LwM2mNodeDecoder;
import org.eclipse.leshan.server.californium.LeshanServerBuilder;
import org.eclipse.leshan.server.cluster.RedisRegistrationStore;
import org.eclipse.leshan.server.cluster.RedisSecurityStore;
import org.eclipse.leshan.server.demo.servlet.ClientServlet;
import org.eclipse.leshan.server.demo.servlet.EventServlet;
import org.eclipse.leshan.server.demo.servlet.ObjectSpecServlet;
import org.eclipse.leshan.server.demo.servlet.SecurityServlet;
import org.eclipse.leshan.server.impl.FileSecurityStore;
import org.eclipse.leshan.server.model.LwM2mModelProvider;
import org.eclipse.leshan.server.registration.Registration;
import org.eclipse.leshan.server.security.EditableSecurityStore;
import org.eclipse.leshan.util.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.util.Pool;

public class LeshanServer {

    static {
        // Define a default logback.configurationFile
        String property = System.getProperty("logback.configurationFile");
        if (property == null) {
            System.setProperty("logback.configurationFile", "logback-config.xml");
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(LeshanServer.class);
    private final static String[] modelPaths = new String[] { "BPMS_objects.json","oma-objects-spec.json" };
    private final static String USAGE = "java -jar leshan-server-demo.jar [OPTION]";
    private final static String DEFAULT_KEYSTORE_TYPE = KeyStore.getDefaultType();
    private final static String DEFAULT_KEYSTORE_ALIAS = "leshan";

    public static void main(String[] args) {
        // Define options for command line tools
        Options options = new Options();

        options.addOption("h", "help", false, "Display help information.");
        options.addOption("lh", "coaphost", true, "Set the local CoAP address.\n  Default: any local address.");
        options.addOption("lp", "coapport", true,
                String.format("Set the local CoAP port.\n  Default: %d.", LwM2m.DEFAULT_COAP_PORT));
        options.addOption("slh", "coapshost", true, "Set the secure local CoAP address.\nDefault: any local address.");
        options.addOption("slp", "coapsport", true,
                String.format("Set the secure local CoAP port.\nDefault: %d.", LwM2m.DEFAULT_COAP_SECURE_PORT));
        options.addOption("ks", "keystore", true,
                "Set the key store file. If set, X.509 mode is enabled, otherwise built-in RPK credentials are used.");
        options.addOption("ksp", "storepass", true, "Set the key store password.");
        options.addOption("kst", "storetype", true,
                String.format("Set the key store type.\nDefault: %s.", DEFAULT_KEYSTORE_TYPE));
        options.addOption("ksa", "alias", true, String.format(
                "Set the key store alias to use for server credentials.\nDefault: %s.", DEFAULT_KEYSTORE_ALIAS));
        options.addOption("ksap", "keypass", true, "Set the key store alias password to use.");
        options.addOption("wh", "webhost", true, "Set the HTTP address for web server.\nDefault: any local address.");
        options.addOption("wp", "webport", true, "Set the HTTP port for web server.\nDefault: 8080.");
        options.addOption("m", "modelsfolder", true, "A folder which contains object models in OMA DDF(.xml) format.");
        options.addOption("r", "redis", true,
                "Set the location of the Redis database for running in cluster mode. The URL is in the format of: 'redis://:password@hostname:port/db_number'\nExample without DB and password: 'redis://localhost:6379'\nDefault: none, no Redis connection.");
        options.addOption("mdns", "publishDNSSdServices", false, "Publish leshan's services to DNS Service discovery");
        HelpFormatter formatter = new HelpFormatter();
        formatter.setOptionComparator(null);

        // Parse arguments
        CommandLine cl;
        try {
            cl = new DefaultParser().parse(options, args);
        } catch (ParseException e) {
            System.err.println("Parsing failed.  Reason: " + e.getMessage());
            formatter.printHelp(USAGE, options);
            return;
        }

        // Print help
        if (cl.hasOption("help")) {
            formatter.printHelp(USAGE, options);
            return;
        }

        // Abort if unexpected options
        if (cl.getArgs().length > 0) {
            System.err.println("Unexpected option or arguments : " + cl.getArgList());
            formatter.printHelp(USAGE, options);
            return;
        }

        // get local address
        String localAddress = cl.getOptionValue("lh");
        String localPortOption = cl.getOptionValue("lp");
        int localPort = LwM2m.DEFAULT_COAP_PORT;
        if (localPortOption != null) {
            localPort = Integer.parseInt(localPortOption);
        }

        // get secure local address
        String secureLocalAddress = cl.getOptionValue("slh");
        String secureLocalPortOption = cl.getOptionValue("slp");
        int secureLocalPort = LwM2m.DEFAULT_COAP_SECURE_PORT;
        if (secureLocalPortOption != null) {
            secureLocalPort = Integer.parseInt(secureLocalPortOption);
        }

        // get http address
        String webAddress = cl.getOptionValue("wh");
        String webPortOption = cl.getOptionValue("wp");
        int webPort = 8080;
        if (webPortOption != null) {
            webPort = Integer.parseInt(webPortOption);
        }

        // Get models folder
        String modelsFolderPath = cl.getOptionValue("m");

        // get the Redis hostname:port
        String redisUrl = cl.getOptionValue("r");

        // Get keystore parameters
        String keyStorePath = cl.getOptionValue("ks");
        String keyStoreType = cl.getOptionValue("kst", KeyStore.getDefaultType());
        String keyStorePass = cl.getOptionValue("ksp");
        String keyStoreAlias = cl.getOptionValue("ksa");
        String keyStoreAliasPass = cl.getOptionValue("ksap");

        // Get mDNS publish switch
        Boolean publishDNSSdServices = cl.hasOption("mdns");

        try {
            createAndStartServer(webAddress, webPort, localAddress, localPort, secureLocalAddress, secureLocalPort,
                    modelsFolderPath, redisUrl, keyStorePath, keyStoreType, keyStorePass, keyStoreAlias,
                    keyStoreAliasPass, publishDNSSdServices);
        } catch (BindException e) {
            System.err.println(
                    String.format("Web port %s is already used, you could change it using 'webport' option.", webPort));
            formatter.printHelp(USAGE, options);
        } catch (Exception e) {
            LOG.error("Jetty stopped with unexpected error ...", e);
        }
    }

    public static void createAndStartServer(String webAddress, int webPort, String localAddress, int localPort,
            String secureLocalAddress, int secureLocalPort, String modelsFolderPath, String redisUrl,
            String keyStorePath, String keyStoreType, String keyStorePass, String keyStoreAlias,
            String keyStoreAliasPass, Boolean publishDNSSdServices) throws Exception {
        // Prepare LWM2M server
        LeshanServerBuilder builder = new LeshanServerBuilder();
        builder.setLocalAddress(localAddress, localPort);
        builder.setLocalSecureAddress(secureLocalAddress, secureLocalPort);
        builder.setEncoder(new DefaultLwM2mNodeEncoder());
        LwM2mNodeDecoder decoder = new DefaultLwM2mNodeDecoder();
        builder.setDecoder(decoder);

        // Create CoAP Config
        NetworkConfig coapConfig;
        File configFile = new File(NetworkConfig.DEFAULT_FILE_NAME);
        if (configFile.isFile()) {
            coapConfig = new NetworkConfig();
            coapConfig.load(configFile);
        } else {
            coapConfig = LeshanServerBuilder.createDefaultNetworkConfig();
            coapConfig.store(configFile);
        }
        builder.setCoapConfig(coapConfig);

        // connect to redis if needed
        Pool<Jedis> jedis = null;
        if (redisUrl != null) {
            // TODO: support sentinel pool and make pool configurable
            jedis = new JedisPool(new URI(redisUrl));
        }

        PublicKey publicKey = null;

        // Set up X.509 mode
        if (keyStorePath != null) {
            try {
                KeyStore keyStore = KeyStore.getInstance(keyStoreType);
                try (FileInputStream fis = new FileInputStream(keyStorePath)) {
                    keyStore.load(fis, keyStorePass == null ? null : keyStorePass.toCharArray());
                    List<Certificate> trustedCertificates = new ArrayList<>();
                    for (Enumeration<String> aliases = keyStore.aliases(); aliases.hasMoreElements();) {
                        String alias = aliases.nextElement();
                        if (keyStore.isCertificateEntry(alias)) {
                            trustedCertificates.add(keyStore.getCertificate(alias));
                        } else if (keyStore.isKeyEntry(alias) && alias.equals(keyStoreAlias)) {
                            List<X509Certificate> x509CertificateChain = new ArrayList<>();
                            Certificate[] certificateChain = keyStore.getCertificateChain(alias);
                            if (certificateChain == null || certificateChain.length == 0) {
                                LOG.error("Keystore alias must have a non-empty chain of X509Certificates.");
                                System.exit(-1);
                            }

                            for (Certificate certificate : certificateChain) {
                                if (!(certificate instanceof X509Certificate)) {
                                    LOG.error("Non-X.509 certificate in alias chain is not supported: {}", certificate);
                                    System.exit(-1);
                                }
                                x509CertificateChain.add((X509Certificate) certificate);
                            }

                            Key key = keyStore.getKey(alias,
                                    keyStoreAliasPass == null ? new char[0] : keyStoreAliasPass.toCharArray());
                            if (!(key instanceof PrivateKey)) {
                                LOG.error("Keystore alias must have a PrivateKey entry, was {}",
                                        key == null ? null : key.getClass().getName());
                                System.exit(-1);
                            }
                            builder.setPrivateKey((PrivateKey) key);
                            publicKey = keyStore.getCertificate(alias).getPublicKey();
                            builder.setCertificateChain(
                                    x509CertificateChain.toArray(new X509Certificate[x509CertificateChain.size()]));
                        }
                    }
                    builder.setTrustedCertificates(
                            trustedCertificates.toArray(new Certificate[trustedCertificates.size()]));
                }
            } catch (KeyStoreException | IOException e) {
                LOG.error("Unable to initialize X.509.", e);
                System.exit(-1);
            }
        }
        // Otherwise, set up RPK mode
        else {
            try {
                // Get point values
                byte[] publicX = Hex
                        .decodeHex("fcc28728c123b155be410fc1c0651da374fc6ebe7f96606e90d927d188894a73".toCharArray());
                byte[] publicY = Hex
                        .decodeHex("d2ffaa73957d76984633fc1cc54d0b763ca0559a9dff9706e9f4557dacc3f52a".toCharArray());
                byte[] privateS = Hex
                        .decodeHex("1dae121ba406802ef07c193c1ee4df91115aabd79c1ed7f4c0ef7ef6a5449400".toCharArray());

                // Get Elliptic Curve Parameter spec for secp256r1
                AlgorithmParameters algoParameters = AlgorithmParameters.getInstance("EC");
                algoParameters.init(new ECGenParameterSpec("secp256r1"));
                ECParameterSpec parameterSpec = algoParameters.getParameterSpec(ECParameterSpec.class);

                // Create key specs
                KeySpec publicKeySpec = new ECPublicKeySpec(
                        new ECPoint(new BigInteger(publicX), new BigInteger(publicY)), parameterSpec);
                KeySpec privateKeySpec = new ECPrivateKeySpec(new BigInteger(privateS), parameterSpec);

                // Get keys
                publicKey = KeyFactory.getInstance("EC").generatePublic(publicKeySpec);
                Files.write(Paths.get("server_pub.der"), publicKey.getEncoded(), StandardOpenOption.CREATE);
                PrivateKey privateKey = KeyFactory.getInstance("EC").generatePrivate(privateKeySpec);
                builder.setPublicKey(publicKey);
                builder.setPrivateKey(privateKey);
            } catch (InvalidKeySpecException | NoSuchAlgorithmException | InvalidParameterSpecException e) {
                LOG.error("Unable to initialize RPK.", e);
                System.exit(-1);
            }
        }

        LwM2mModelProvider modelProvider = new LwM2mModelProvider() {
            @Override
            public LwM2mModel getObjectModel(Registration client) {
                InputStream defaultSpec = LeshanServer.class.getResourceAsStream("/models/oma-objects-spec.json");
                InputStream bpmSpec = LeshanServer.class.getResourceAsStream("/models/BPMS_objects.json");
                List<ObjectModel> models = ObjectLoader.loadJsonStream(defaultSpec);
                models.addAll(ObjectLoader.loadJsonStream(bpmSpec));
                return new LwM2mModel(models);
            }
        };


        builder.setObjectModelProvider(modelProvider);

        // Set securityStore & registrationStore
        EditableSecurityStore securityStore;
        if (jedis == null) {
            // use file persistence
            securityStore = new FileSecurityStore();
        } else {
            // use Redis Store
            securityStore = new RedisSecurityStore(jedis);
            builder.setRegistrationStore(new RedisRegistrationStore(jedis));
        }
        builder.setSecurityStore(securityStore);

        // Create and start LWM2M server
        org.eclipse.leshan.server.californium.impl.LeshanServer lwServer = builder.build();

        // Now prepare Jetty
        InetSocketAddress jettyAddr;
        if (webAddress == null) {
            jettyAddr = new InetSocketAddress(webPort);
        } else {
            jettyAddr = new InetSocketAddress(webAddress, webPort);
        }
        Server server = new Server(jettyAddr);
        WebAppContext root = new WebAppContext();
        root.setContextPath("/");
        root.setResourceBase(LeshanServer.class.getClassLoader().getResource("webapp").toExternalForm());
        root.setParentLoaderPriority(true);
        server.setHandler(root);

        // Create Servlet
        EventServlet eventServlet = new EventServlet(lwServer, lwServer.getSecuredAddress().getPort());
        ServletHolder eventServletHolder = new ServletHolder(eventServlet);
        root.addServlet(eventServletHolder, "/event/*");

        ServletHolder clientServletHolder = new ServletHolder(
                new ClientServlet(lwServer, lwServer.getSecuredAddress().getPort()));
        root.addServlet(clientServletHolder, "/api/clients/*");

        ServletHolder securityServletHolder = new ServletHolder(new SecurityServlet(securityStore, publicKey));
        root.addServlet(securityServletHolder, "/api/security/*");

        ServletHolder objectSpecServletHolder = new ServletHolder(new ObjectSpecServlet(lwServer.getModelProvider()));
        root.addServlet(objectSpecServletHolder, "/api/objectspecs/*");

        // Register a service to DNS-SD
        if (publishDNSSdServices) {

            // Create a JmDNS instance
            JmDNS jmdns = JmDNS.create(InetAddress.getLocalHost());

            // Publish Leshan HTTP Service
            ServiceInfo httpServiceInfo = ServiceInfo.create("_http._tcp.local.", "leshan", webPort, "");
            jmdns.registerService(httpServiceInfo);

            // Publish Leshan CoAP Service
            ServiceInfo coapServiceInfo = ServiceInfo.create("_coap._udp.local.", "leshan", localPort, "");
            jmdns.registerService(coapServiceInfo);

            // Publish Leshan Secure CoAP Service
            ServiceInfo coapSecureServiceInfo = ServiceInfo.create("_coaps._udp.local.", "leshan", secureLocalPort, "");
            jmdns.registerService(coapSecureServiceInfo);
        }

        // Start Jetty & Leshan
        lwServer.start();
        server.start();
        LOG.info("Web server started at {}.", server.getURI());
    }
}
