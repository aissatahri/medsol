-- Spécialités : « Dentaire » n'est plus proposée à l'inscription (gardée pour un cabinet existant et ses données) ;
-- 15 spécialités ajoutées, chacune avec ses champs de consultation et, si utile, ses constantes.

ALTER TABLE specialite ADD COLUMN proposee boolean NOT NULL DEFAULT true;
UPDATE specialite SET proposee = false WHERE code = 'DENTAIRE';

INSERT INTO specialite (code, libelle) VALUES
    ('ENDOCRINO',   '{"fr":"Endocrinologie - Diabétologie","en":"Endocrinology - Diabetes","es":"Endocrinología - Diabetes","ar":"أمراض الغدد والسكري"}'),
    ('GASTRO',      '{"fr":"Gastro-entérologie","en":"Gastroenterology","es":"Gastroenterología","ar":"أمراض الجهاز الهضمي"}'),
    ('PNEUMO',      '{"fr":"Pneumologie","en":"Pulmonology","es":"Neumología","ar":"أمراض الرئة والجهاز التنفسي"}'),
    ('NEPHRO',      '{"fr":"Néphrologie","en":"Nephrology","es":"Nefrología","ar":"أمراض الكلى"}'),
    ('NEURO',       '{"fr":"Neurologie","en":"Neurology","es":"Neurología","ar":"طب الأعصاب"}'),
    ('PSYCHIATRIE', '{"fr":"Psychiatrie","en":"Psychiatry","es":"Psiquiatría","ar":"الطب النفسي"}'),
    ('RHUMATO',     '{"fr":"Rhumatologie","en":"Rheumatology","es":"Reumatología","ar":"أمراض الروماتيزم"}'),
    ('ORL',         '{"fr":"ORL (oto-rhino-laryngologie)","en":"ENT (otorhinolaryngology)","es":"Otorrinolaringología","ar":"أمراض الأنف والأذن والحنجرة"}'),
    ('UROLOGIE',    '{"fr":"Urologie","en":"Urology","es":"Urología","ar":"جراحة المسالك البولية"}'),
    ('TRAUMATO',    '{"fr":"Traumatologie - Orthopédie","en":"Orthopaedics - Traumatology","es":"Traumatología - Ortopedia","ar":"جراحة العظام والمفاصل"}'),
    ('CHIRURGIE',   '{"fr":"Chirurgie générale","en":"General surgery","es":"Cirugía general","ar":"الجراحة العامة"}'),
    ('MED_INTERNE', '{"fr":"Médecine interne","en":"Internal medicine","es":"Medicina interna","ar":"الطب الباطني"}'),
    ('ONCOLOGIE',   '{"fr":"Oncologie","en":"Oncology","es":"Oncología","ar":"طب الأورام"}'),
    ('NUTRITION',   '{"fr":"Nutrition","en":"Nutrition","es":"Nutrición","ar":"التغذية"}'),
    ('MED_TRAVAIL', '{"fr":"Médecine du travail","en":"Occupational medicine","es":"Medicina del trabajo","ar":"طب الشغل"}');

-- Champs de consultation propres à chaque spécialité (après les 4 champs communs : symptômes, examen, diagnostic, conduite)
INSERT INTO type_champ_clinique (specialite, nom, libelle, ordre) VALUES
    ('ENDOCRINO',   'EQUILIBRE_GLYCEMIQUE', '{"fr":"Équilibre glycémique (HbA1c, autosurveillance)","en":"Glycaemic control (HbA1c, self-monitoring)","es":"Control glucémico (HbA1c, autocontrol)"}', 5),
    ('ENDOCRINO',   'PIED_DIABETIQUE',      '{"fr":"Examen des pieds","en":"Foot examination","es":"Exploración de los pies"}', 6),
    ('ENDOCRINO',   'THYROIDE',             '{"fr":"Thyroïde (examen, bilan)","en":"Thyroid (exam, tests)","es":"Tiroides (exploración, analítica)"}', 7),
    ('GASTRO',      'EXAMEN_ABDOMINAL',     '{"fr":"Examen abdominal","en":"Abdominal examination","es":"Exploración abdominal"}', 5),
    ('GASTRO',      'ENDOSCOPIE',           '{"fr":"Endoscopie (FOGD, coloscopie)","en":"Endoscopy (upper GI, colonoscopy)","es":"Endoscopia (gastroscopia, colonoscopia)"}', 6),
    ('PNEUMO',      'AUSCULTATION',         '{"fr":"Auscultation pulmonaire","en":"Lung auscultation","es":"Auscultación pulmonar"}', 5),
    ('PNEUMO',      'EFR',                  '{"fr":"EFR / spirométrie","en":"Lung function / spirometry","es":"Pruebas funcionales / espirometría"}', 6),
    ('PNEUMO',      'RADIO_THORAX',         '{"fr":"Radiographie / scanner thoracique","en":"Chest X-ray / CT","es":"Radiografía / TAC de tórax"}', 7),
    ('NEPHRO',      'FONCTION_RENALE',      '{"fr":"Fonction rénale (créatinine, DFG, protéinurie)","en":"Kidney function (creatinine, eGFR, proteinuria)","es":"Función renal (creatinina, FGe, proteinuria)"}', 5),
    ('NEPHRO',      'DIALYSE',              '{"fr":"Dialyse / abord vasculaire","en":"Dialysis / vascular access","es":"Diálisis / acceso vascular"}', 6),
    ('NEURO',       'EXAMEN_NEURO',         '{"fr":"Examen neurologique","en":"Neurological examination","es":"Exploración neurológica"}', 5),
    ('NEURO',       'EEG_EMG',              '{"fr":"EEG / EMG","en":"EEG / EMG","es":"EEG / EMG"}', 6),
    ('PSYCHIATRIE', 'EXAMEN_PSY',           '{"fr":"Examen psychiatrique (état mental)","en":"Mental state examination","es":"Exploración psicopatológica"}', 5),
    ('PSYCHIATRIE', 'RISQUE_SUICIDAIRE',    '{"fr":"Évaluation du risque suicidaire","en":"Suicide risk assessment","es":"Evaluación del riesgo suicida"}', 6),
    ('PSYCHIATRIE', 'SUIVI_PSY',            '{"fr":"Suivi et psychothérapie","en":"Follow-up and psychotherapy","es":"Seguimiento y psicoterapia"}', 7),
    ('RHUMATO',     'EXAMEN_ARTICULAIRE',   '{"fr":"Examen articulaire et rachidien","en":"Joint and spine examination","es":"Exploración articular y de columna"}', 5),
    ('RHUMATO',     'IMAGERIE_OSTEO',       '{"fr":"Imagerie (radio, échographie, IRM)","en":"Imaging (X-ray, ultrasound, MRI)","es":"Imagen (radiografía, ecografía, RM)"}', 6),
    ('ORL',         'OTOSCOPIE',            '{"fr":"Otoscopie","en":"Otoscopy","es":"Otoscopia"}', 5),
    ('ORL',         'NEZ_GORGE',            '{"fr":"Rhinoscopie / examen de la gorge","en":"Rhinoscopy / throat examination","es":"Rinoscopia / exploración de la garganta"}', 6),
    ('ORL',         'AUDIOGRAMME',          '{"fr":"Audiogramme / tympanométrie","en":"Audiogram / tympanometry","es":"Audiograma / timpanometría"}', 7),
    ('UROLOGIE',    'EXAMEN_URO',           '{"fr":"Examen urologique (TR)","en":"Urological examination (DRE)","es":"Exploración urológica (tacto rectal)"}', 5),
    ('UROLOGIE',    'ECHO_URO',             '{"fr":"Échographie / débitmétrie","en":"Ultrasound / uroflowmetry","es":"Ecografía / flujometría"}', 6),
    ('TRAUMATO',    'EXAMEN_OSTEO',         '{"fr":"Examen ostéo-articulaire","en":"Musculoskeletal examination","es":"Exploración osteoarticular"}', 5),
    ('TRAUMATO',    'RADIOGRAPHIES',        '{"fr":"Radiographies","en":"X-rays","es":"Radiografías"}', 6),
    ('TRAUMATO',    'IMMOBILISATION',       '{"fr":"Immobilisation / soins (plâtre, attelle, pansement)","en":"Immobilisation / care (cast, splint, dressing)","es":"Inmovilización / curas (yeso, férula, apósito)"}', 7),
    ('CHIRURGIE',   'EXAMEN_CHIRURGICAL',   '{"fr":"Examen chirurgical","en":"Surgical examination","es":"Exploración quirúrgica"}', 5),
    ('CHIRURGIE',   'INDICATION_OPERATOIRE','{"fr":"Indication opératoire / bilan préopératoire","en":"Surgical indication / pre-op work-up","es":"Indicación quirúrgica / estudio preoperatorio"}', 6),
    ('CHIRURGIE',   'SUITES_OPERATOIRES',   '{"fr":"Suites opératoires / pansement","en":"Post-operative course / dressing","es":"Evolución postoperatoria / curas"}', 7),
    ('MED_INTERNE', 'SYNTHESE',             '{"fr":"Synthèse clinique","en":"Clinical summary","es":"Síntesis clínica"}', 5),
    ('MED_INTERNE', 'BILAN_BIOLOGIQUE',     '{"fr":"Bilan biologique","en":"Laboratory results","es":"Analítica"}', 6),
    ('ONCOLOGIE',   'STADE',                '{"fr":"Diagnostic histologique / stade (TNM)","en":"Histology / stage (TNM)","es":"Histología / estadio (TNM)"}', 5),
    ('ONCOLOGIE',   'PROTOCOLE',            '{"fr":"Protocole / cycle de traitement","en":"Treatment protocol / cycle","es":"Protocolo / ciclo de tratamiento"}', 6),
    ('ONCOLOGIE',   'TOLERANCE',            '{"fr":"Tolérance et effets indésirables","en":"Tolerance and side effects","es":"Tolerancia y efectos adversos"}', 7),
    ('NUTRITION',   'BILAN_ALIMENTAIRE',    '{"fr":"Bilan alimentaire","en":"Dietary assessment","es":"Valoración dietética"}', 5),
    ('NUTRITION',   'OBJECTIFS',            '{"fr":"Objectifs et plan nutritionnel","en":"Goals and nutrition plan","es":"Objetivos y plan nutricional"}', 6),
    ('MED_TRAVAIL', 'POSTE_TRAVAIL',        '{"fr":"Poste de travail / exposition aux risques","en":"Job / occupational exposure","es":"Puesto de trabajo / exposición a riesgos"}', 5),
    ('MED_TRAVAIL', 'APTITUDE',             '{"fr":"Aptitude (apte, apte avec réserves, inapte)","en":"Fitness for work","es":"Aptitud laboral"}', 6);

-- Constantes propres à certaines spécialités (les constantes communes restent : poids, taille, IMC, TA, pouls…)
INSERT INTO type_constante (specialite, nom, libelle, unite, complexe, ordre) VALUES
    ('ENDOCRINO', 'HBA1C',        '{"fr":"HbA1c","en":"HbA1c","es":"HbA1c"}',                                   '%',     false, 95),
    ('NUTRITION', 'TOUR_TAILLE',  '{"fr":"Tour de taille","en":"Waist circumference","es":"Perímetro de cintura"}', 'cm',    false, 35),
    ('PNEUMO',    'DEP',          '{"fr":"Débit expiratoire de pointe","en":"Peak expiratory flow","es":"Flujo espiratorio máximo"}', 'L/min', false, 85),
    ('RHUMATO',   'EVA_DOULEUR',  '{"fr":"Douleur (EVA)","en":"Pain score (VAS)","es":"Dolor (EVA)"}',             '/10',   false, 100),
    ('NEURO',     'GLASGOW',      '{"fr":"Score de Glasgow","en":"Glasgow Coma Scale","es":"Escala de Glasgow"}',  '/15',   false, 100);
