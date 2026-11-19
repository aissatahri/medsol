# Sauvegardes chiffrées de medsol_db (même version majeure que le serveur PostgreSQL)
FROM postgres:16-alpine
# rclone : copie hors du serveur (S3, Backblaze, OVH, Google Drive...) via MEDSOL_BACKUP_COPY_CMD
RUN apk add --no-cache openssl rclone
COPY sauvegarde.sh restaurer.sh tester-restauration.sh /usr/local/bin/
# fins de ligne Unix même si les scripts ont été édités sous Windows
RUN sed -i 's/\r$//' /usr/local/bin/*.sh && chmod 755 /usr/local/bin/*.sh
VOLUME /sauvegardes
ENTRYPOINT ["/usr/local/bin/sauvegarde.sh"]
CMD ["boucle"]
