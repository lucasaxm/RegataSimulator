package com.boatarde.regatasimulator.migration;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

final class OfflinePaths {
    private OfflinePaths() { }
    static Path checked(Path path) throws IOException {
        if (!path.isAbsolute()) throw new IOException("Absolute paths required");
        Path normalized=path.normalize();
        for(Path part=normalized;part!=null;part=part.getParent()) {
            if(Files.isSymbolicLink(part)) throw new IOException("Symbolic links forbidden");
        }
        return normalized;
    }
    static void newTarget(Path target,List<Path> inputs) throws IOException {
        checked(target);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(target.getParent())) throw new IOException("Target must be new with an existing parent");
        for(Path input:inputs) {
            checked(input);
            if(target.startsWith(input) || input.startsWith(target)) throw new IOException("Input and output paths overlap");
        }
    }
    static Map<String,String> hashes(Path root) throws IOException {
        checked(root);
        if(!Files.isDirectory(root)) throw new IOException("Snapshot directory absent");
        Map<String,String> hashes=new TreeMap<>();
        try(var paths=Files.walk(root)) {
            for(Path path:paths.sorted().toList()) {
                checked(path);
                if(Files.isRegularFile(path)) hashes.put(root.relativize(path).toString(),hash(path));
                else if(!Files.isDirectory(path)) throw new IOException("Unsupported snapshot entry");
            }
        }
        return hashes;
    }
    static String hash(Path path) throws IOException {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(path)) {
                byte[] buffer=new byte[8192];
                int size;
                while((size=input.read(buffer))!=-1) digest.update(buffer,0,size);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static void copyTree(Path source,Path target) throws IOException {
        Files.createDirectory(target);
        try(var paths=Files.walk(source)) {
            for(Path path:paths.sorted().toList()) {
                checked(path);
                if(path.equals(source)) continue;
                Path copy=target.resolve(source.relativize(path));
                if(Files.isDirectory(path)) Files.createDirectory(copy); else Files.copy(path,copy);
            }
        }
    }
}