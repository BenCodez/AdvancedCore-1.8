package com.bencodez.advancedcore.api.permissions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import lombok.Getter;

public class PlayerPermissionHandler {
    @Getter private UUID uuid;
    @Getter private PermissionAttachment attachment;
    private PermissionHandler handler;
    @Getter private HashMap<String, Long> timedPermissions = new HashMap<>();
    private HashMap<String, Long> permsToAdd = new HashMap<>();
    private final Set<String> persistentPermissions = new HashSet<>();

    public PlayerPermissionHandler(UUID uuid, PermissionAttachment attachment, PermissionHandler handler) {
        this.uuid=uuid;this.attachment=attachment;this.handler=handler;
    }

    /** Preserve the legacy setter signature while fencing retired manager admission. */
    public void setAttachment(PermissionAttachment attachment) {
        synchronized(handler) {
            handler.requireOpen();
            synchronized(this) { this.attachment=attachment; }
        }
    }

    /** Existing public duration remains seconds; persisted timestamps remain epoch milliseconds. */
    public PlayerPermissionHandler addExpiration(String perm, long seconds) {
        synchronized(handler) {
            handler.requireOpen();
            synchronized(this) {
                if(seconds<=0)return addPerm(perm);
                return restoreExpiration(perm,Math.addExact(System.currentTimeMillis(),Math.multiplyExact(seconds,1000L)));
            }
        }
    }

    PlayerPermissionHandler restoreExpiration(String perm,long expireAt) {
        synchronized(handler) {
            handler.requireOpen();
            synchronized(this) {
                if(expireAt<=System.currentTimeMillis())return this;
                handler.scheduleExpiration(this,perm,expireAt,Math.max(0L,expireAt-System.currentTimeMillis()));
                timedPermissions.put(perm,expireAt);persistentPermissions.remove(perm);
                if(attachment!=null)attachment.setPermission(perm,true);
                return this;
            }
        }
    }

    /** Fresh offline grants begin their seconds-based duration on login, as in current main. */
    public PlayerPermissionHandler addOfflinePerm(String perm,long seconds) {
        synchronized(handler) {
            handler.requireOpen();
            synchronized(this) {
                permsToAdd.put(perm,seconds);return this;
            }
        }
    }

    synchronized HashMap<String,Long> offlinePermissionSnapshot(){return new HashMap<>(permsToAdd);}
    synchronized void mergeOfflinePermissions(java.util.Map<String,Long> pending){permsToAdd.putAll(pending);}

    public PlayerPermissionHandler addPerm(String perm) {
        synchronized(handler) {
            handler.requireOpen();
            synchronized(this) {
                persistentPermissions.add(perm);timedPermissions.remove(perm);
                if(attachment!=null)attachment.setPermission(perm,true);
                return this;
            }
        }
    }

    public void onLogin(Player player) {
        synchronized(handler) {
            handler.requireOpen();
            synchronized(this) {
                if(player==null || attachment==null)return;
                for(String perm:persistentPermissions)attachment.setPermission(perm,true);
                long now=System.currentTimeMillis();
                for(Entry<String,Long> entry:new HashMap<>(timedPermissions).entrySet()) {
                    if(entry.getValue()>now)attachment.setPermission(entry.getKey(),true);
                    else timedPermissions.remove(entry.getKey());
                }
                for(Entry<String,Long> entry:new HashMap<>(permsToAdd).entrySet()) {
                    if(entry.getValue()>0)addExpiration(entry.getKey(),entry.getValue());
                    else addPerm(entry.getKey());
                    permsToAdd.remove(entry.getKey(),entry.getValue());
                }
            }
        }
    }

    synchronized HashMap<String,Long> timedPermissionSnapshot(){return new HashMap<>(timedPermissions);}
    synchronized boolean isExpirationCurrent(String perm,long expected){Long current=timedPermissions.get(perm);return current!=null && current.longValue()==expected;}

    void expirePermission(String perm,long expected,boolean updateAttachment) {
        boolean empty;
        synchronized(this) {
            if(!isExpirationCurrent(perm,expected))return;
            long remaining=expected-System.currentTimeMillis();
            if(remaining>0){handler.scheduleExpiration(this,perm,expected,remaining);return;}
            timedPermissions.remove(perm);
            if(updateAttachment && attachment!=null)attachment.unsetPermission(perm);
            empty=isEmpty();
        }
        if(empty)handler.removePermissionIfEmpty(uuid,this);
    }

    synchronized boolean isEmpty(){return timedPermissions.isEmpty() && persistentPermissions.isEmpty() && permsToAdd.isEmpty();}

    public void remove() {
        synchronized(this){if(attachment!=null)attachment.remove();timedPermissions.clear();persistentPermissions.clear();permsToAdd.clear();}
        handler.removePermissionIfEmpty(uuid,this);
    }

    public void removePermission(String perm) {
        boolean empty;
        synchronized(this) {
            timedPermissions.remove(perm);persistentPermissions.remove(perm);permsToAdd.remove(perm);
            if(attachment!=null)attachment.setPermission(perm,false);
            empty=isEmpty();
        }
        if(empty)handler.removePermissionIfEmpty(uuid,this);
    }
}
