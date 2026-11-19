-- Espagnol : libellés de référence (spécialités, constantes, champs cliniques, formule). Les ajouts des cabinets et
-- les catalogues importés (examens) gardent leurs langues d'origine ; l'application retombe alors sur le français.

UPDATE specialite SET libelle = libelle || jsonb_build_object('es', v.es)
FROM (VALUES ('GENERAL', 'Medicina general'), ('CARDIO', 'Cardiología'), ('PEDIATRIE', 'Pediatría'),
             ('GYNECO', 'Ginecología'), ('DERMATO', 'Dermatología'), ('DENTAIRE', 'Odontología'),
             ('OPHTALMO', 'Oftalmología'), ('AUTRE', 'Otra')) AS v(code, es)
WHERE specialite.code = v.code;

UPDATE type_constante SET libelle = libelle || jsonb_build_object('es', v.es)
FROM (VALUES ('POIDS', 'Peso'), ('TAILLE', 'Talla'), ('IMC', 'IMC'), ('TEMPERATURE', 'Temperatura'),
             ('FREQUENCE_CARDIAQUE', 'FC (pulso)'), ('PRESSION_ARTERIELLE', 'Presión arterial'),
             ('FREQUENCE_RESPIRATOIRE', 'Frec. respiratoria'), ('SAO2', 'SatO2'), ('GLYCEMIE', 'Glucemia'),
             ('PERIMETRE_CRANIEN', 'Perímetro cefálico'), ('ACUITE_VISUELLE', 'Agudeza visual'),
             ('TONUS_OCULAIRE', 'Presión intraocular')) AS v(nom, es)
WHERE type_constante.cabinet_id IS NULL AND type_constante.nom = v.nom;

UPDATE type_constante_cle k SET libelle = k.libelle || jsonb_build_object('es', v.es)
FROM type_constante t, (VALUES ('PRESSION_ARTERIELLE', 'BRAS_GAUCHE', 'PA brazo izq.'), ('PRESSION_ARTERIELLE', 'BRAS_DROIT', 'PA brazo der.'),
             ('ACUITE_VISUELLE', 'OD', 'OD'), ('ACUITE_VISUELLE', 'OG', 'OI'),
             ('TONUS_OCULAIRE', 'OD', 'OD'), ('TONUS_OCULAIRE', 'OG', 'OI')) AS v(type, cle, es)
WHERE k.type_constante_id = t.id AND t.cabinet_id IS NULL AND t.nom = v.type AND k.cle = v.cle;

UPDATE type_champ_clinique SET libelle = libelle || jsonb_build_object('es', v.es)
FROM (VALUES ('SYMPTOMES', 'Síntomas'), ('EXAMEN_CLINIQUE', 'Exploración física'), ('DIAGNOSTIC_MEDICAL', 'Diagnóstico'),
             ('CONDUITE_TENIR', 'Plan terapéutico'), ('ECG', 'ECG'), ('ETT', 'ETT / Ecocardiografía'),
             ('DEVELOPPEMENT', 'Desarrollo psicomotor'), ('VACCINATION', 'Vacunación'),
             ('DDR', 'Fecha de la última regla'), ('GESTITE_PARITE', 'Gestas / partos'),
             ('EXAMEN_GYNECO', 'Exploración ginecológica'), ('LESIONS', 'Descripción de las lesiones'),
             ('SCHEMA_DENTAIRE', 'Dientes afectados'), ('SOINS_DENTAIRES', 'Tratamientos realizados'),
             ('FOND_OEIL', 'Fondo de ojo'), ('REFRACTION', 'Refracción')) AS v(nom, es)
WHERE type_champ_clinique.cabinet_id IS NULL AND type_champ_clinique.nom = v.nom;

UPDATE formule SET nom = nom || '{"es":"MedSol Consultorio"}',
                   description = description || '{"es":"Todas las funciones para un médico y su asistente."}'
WHERE code = 'CABINET';
