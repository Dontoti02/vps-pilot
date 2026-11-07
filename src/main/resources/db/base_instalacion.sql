-- phpMyAdmin SQL Dump
-- version 5.2.1
-- https://www.phpmyadmin.net/
--
-- Servidor: 127.0.0.1
-- Tiempo de generación: 27-10-2026 a las 17:31:58
-- Versión del servidor: 11.8.2-MariaDB
-- Versión de PHP: 8.2.12

SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";

START TRANSACTION;

SET time_zone = "+00:00";



/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;

/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;

/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;

/*!40101 SET NAMES utf8mb4 */;


--
-- Base de datos: `db_blaboral`
--

-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `cache`
--

CREATE TABLE `cache` (
  `key` varchar(255) NOT NULL,
  `value` mediumtext NOT NULL,
  `expiration` int(11) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `cache_locks`
--

CREATE TABLE `cache_locks` (
  `key` varchar(255) NOT NULL,
  `owner` varchar(255) NOT NULL,
  `expiration` int(11) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `failed_jobs`
--

CREATE TABLE `failed_jobs` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `uuid` varchar(255) NOT NULL,
  `connection` text NOT NULL,
  `queue` text NOT NULL,
  `payload` longtext NOT NULL,
  `exception` longtext NOT NULL,
  `failed_at` timestamp NOT NULL DEFAULT current_timestamp()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `file`
--

CREATE TABLE `file` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `fileable_type` varchar(255) NOT NULL,
  `fileable_id` bigint(20) UNSIGNED NOT NULL,
  `name` varchar(255) NOT NULL,
  `url` text NOT NULL,
  `metadata` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (json_valid(`metadata`)),
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `jobs`
--

CREATE TABLE `jobs` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `queue` varchar(255) NOT NULL,
  `payload` longtext NOT NULL,
  `attempts` tinyint(3) UNSIGNED NOT NULL,
  `reserved_at` int(10) UNSIGNED DEFAULT NULL,
  `available_at` int(10) UNSIGNED NOT NULL,
  `created_at` int(10) UNSIGNED NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `job_opportunity_applications`
--

CREATE TABLE `job_opportunity_applications` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `fullname` varchar(255) NOT NULL,
  `program_study` varchar(255) DEFAULT NULL,
  `message` text DEFAULT NULL,
  `status` varchar(255) NOT NULL DEFAULT 'postulated',
  `cv` varchar(255) DEFAULT NULL,
  `feedback` text DEFAULT NULL,
  `feedback_date` datetime DEFAULT NULL,
  `offer_id` bigint(20) UNSIGNED NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `job_opportunity_company`
--

CREATE TABLE `job_opportunity_company` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `name` varchar(255) NOT NULL,
  `ruc` varchar(255) NOT NULL,
  `email` varchar(255) NOT NULL,
  `phone` varchar(255) NOT NULL,
  `mailbox` varchar(255) NOT NULL,
  `is_verified` tinyint(1) NOT NULL DEFAULT 0,
  `description` text DEFAULT NULL,
  `website` varchar(255) DEFAULT NULL,
  `address` varchar(255) DEFAULT NULL,
  `logo` varchar(255) DEFAULT NULL,
  `cover_image` varchar(500) DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `job_opportunity_contract_types`
--

CREATE TABLE `job_opportunity_contract_types` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `name` varchar(255) NOT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


--
-- Volcado de datos para la tabla `job_opportunity_contract_types`
--

INSERT INTO `job_opportunity_contract_types` (`id`, `name`, `created_at`, `updated_at`, `deleted_at`) VALUES
(1, 'Contrato a plazo indeterminado', '2025-06-03 09:58:30', '2025-06-03 09:58:30', NULL),
(2, 'Contrato a plazo fijo', '2025-06-03 09:58:30', '2025-06-03 09:58:30', NULL),
(3, 'Contrato por temporada', '2025-06-03 09:58:30', '2025-06-03 09:58:30', NULL),
(4, 'Largo plazo', '2026-06-29 14:10:56', '2026-06-30 15:10:35', NULL);


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `job_opportunity_modalities`
--

CREATE TABLE `job_opportunity_modalities` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `name` varchar(255) NOT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


--
-- Volcado de datos para la tabla `job_opportunity_modalities`
--

INSERT INTO `job_opportunity_modalities` (`id`, `name`, `created_at`, `updated_at`, `deleted_at`) VALUES
(1, 'Remoto', '2025-06-03 09:58:30', '2025-06-03 09:58:30', NULL),
(2, 'Presencial', '2025-06-03 09:58:30', '2025-06-03 09:58:30', NULL),
(3, 'Híbrido', '2025-06-03 09:58:30', '2025-06-03 09:58:30', NULL);


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `job_opportunity_offer`
--

CREATE TABLE `job_opportunity_offer` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `title` varchar(255) NOT NULL,
  `slug` varchar(255) NOT NULL,
  `description` text NOT NULL,
  `requirements` text NOT NULL,
  `publication_date` datetime NOT NULL,
  `deadline` datetime DEFAULT NULL,
  `benefits` text DEFAULT NULL,
  `salary` decimal(10,2) NOT NULL,
  `salary_currency` varchar(255) NOT NULL,
  `vacancies` int(10) UNSIGNED NOT NULL DEFAULT 1,
  `attachments` varchar(255) DEFAULT NULL,
  `banner_image` varchar(500) DEFAULT NULL,
  `external_url` varchar(500) DEFAULT NULL,
  `address` varchar(255) NOT NULL,
  `department` varchar(255) NOT NULL,
  `province` varchar(255) NOT NULL,
  `country` varchar(255) DEFAULT NULL,
  `company_id` bigint(20) UNSIGNED NOT NULL,
  `modality_id` bigint(20) UNSIGNED NOT NULL,
  `state_id` bigint(20) UNSIGNED NOT NULL,
  `category_id` bigint(20) UNSIGNED NOT NULL,
  `work_schedule_id` bigint(20) UNSIGNED NOT NULL,
  `contract_type_id` bigint(20) UNSIGNED NOT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `job_opportunity_offer_category`
--

CREATE TABLE `job_opportunity_offer_category` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `name` varchar(255) NOT NULL,
  `description` varchar(255) DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


--
-- Volcado de datos para la tabla `job_opportunity_offer_category`
--

INSERT INTO `job_opportunity_offer_category` (`id`, `name`, `description`, `created_at`, `updated_at`, `deleted_at`) VALUES
(1, 'Informatica/Tecnologia', NULL, '2025-06-03 09:58:30', '2025-06-03 09:58:30', NULL),
(2, 'Marketing', NULL, '2025-06-03 09:58:30', '2025-06-03 09:58:30', NULL),
(3, 'Administrativo', NULL, '2025-06-03 09:58:30', '2025-06-03 09:58:30', NULL),
(4, 'Tecnologia', NULL, '2026-06-25 19:51:07', '2026-06-25 19:51:07', NULL),
(5, 'Secretaria', NULL, '2026-06-25 22:11:59', '2026-06-25 22:12:06', '2026-06-25 22:12:06');


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `job_opportunity_offer_state`
--

CREATE TABLE `job_opportunity_offer_state` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `name` varchar(255) NOT NULL,
  `key` varchar(255) NOT NULL,
  `description` varchar(255) DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


--
-- Volcado de datos para la tabla `job_opportunity_offer_state`
--

INSERT INTO `job_opportunity_offer_state` (`id`, `name`, `key`, `description`, `created_at`, `updated_at`, `deleted_at`) VALUES
(1, 'Borrador', 'draft', NULL, '2025-06-03 09:58:30', '2025-06-03 09:58:30', NULL),
(2, 'Vigente', 'active', NULL, '2025-06-03 09:58:30', '2025-06-03 09:58:30', NULL),
(3, 'Finalizada', 'finished', NULL, '2025-06-03 09:58:30', '2025-06-03 09:58:30', NULL),
(4, 'Suspendida', 'suspended', NULL, '2025-06-03 09:58:30', '2025-06-03 09:58:30', NULL),
(5, 'Cancelada', 'canceled', NULL, '2025-06-03 09:58:30', '2025-06-03 09:58:30', NULL);


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `job_opportunity_offer_state_detail`
--

CREATE TABLE `job_opportunity_offer_state_detail` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `offer_id` bigint(20) UNSIGNED NOT NULL,
  `state_id` bigint(20) UNSIGNED NOT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `job_opportunity_user_cv`
--

CREATE TABLE `job_opportunity_user_cv` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `version` varchar(255) NOT NULL,
  `url` varchar(255) NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `job_opportunity_work_schedules`
--

CREATE TABLE `job_opportunity_work_schedules` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `name` varchar(255) NOT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


--
-- Volcado de datos para la tabla `job_opportunity_work_schedules`
--

INSERT INTO `job_opportunity_work_schedules` (`id`, `name`, `created_at`, `updated_at`, `deleted_at`) VALUES
(1, 'Jornada Completa', '2025-06-03 09:58:30', '2025-06-03 09:58:30', NULL),
(2, 'Becas/Prácticas', '2025-06-03 09:58:30', '2025-06-03 09:58:30', NULL),
(3, 'Jornada Parcial', '2025-06-03 09:58:30', '2025-06-03 09:58:30', NULL),
(4, 'Por Horas', '2025-06-03 09:58:30', '2025-06-03 09:58:30', NULL);


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `link`
--

CREATE TABLE `link` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `linkable_type` varchar(255) NOT NULL,
  `linkable_id` bigint(20) UNSIGNED NOT NULL,
  `url` text NOT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `menu`
--

CREATE TABLE `menu` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `name` varchar(255) NOT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


--
-- Volcado de datos para la tabla `menu`
--

INSERT INTO `menu` (`id`, `name`, `created_at`, `updated_at`, `deleted_at`) VALUES
(1, 'Principal', '2025-06-03 09:58:29', '2025-06-03 09:58:29', NULL),
(2, 'Aula virtual', '2025-06-03 09:58:29', '2025-06-03 09:58:29', NULL),
(3, 'Preferencias', '2025-06-03 09:58:29', '2025-06-03 09:58:29', NULL),
(4, 'Configuración', '2025-06-03 09:58:29', '2025-06-03 09:58:29', NULL),
(5, 'Acceso rápido', '2025-06-03 09:58:29', '2025-06-03 09:58:29', NULL),
(6, 'Bolsa Laboral', '2025-06-03 09:58:29', '2025-06-03 09:58:29', NULL);


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `migrations`
--

CREATE TABLE `migrations` (
  `id` int(10) UNSIGNED NOT NULL,
  `migration` varchar(255) NOT NULL,
  `batch` int(11) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


--
-- Volcado de datos para la tabla `migrations`
--

INSERT INTO `migrations` (`id`, `migration`, `batch`) VALUES
(1, '2024_04_08_145119_create_job_opportunity_company_table', 1),
(2, '2024_04_08_145119_create_person_table', 1),
(3, '2024_04_08_145119_create_system_configuration_table', 1),
(4, '2024_04_08_145120_create_user_table', 1),
(5, '2024_04_08_231525_create_file_table', 1),
(6, '2024_04_08_231525_create_link_table', 1),
(7, '2024_05_15_104107_create_cache_table', 1),
(8, '2024_05_15_110412_create_jobs_table', 1),
(9, '2024_05_15_151348_create_failed_jobs_table', 1),
(10, '2025_05_14_114152_create_job_opportunity_location_table', 1),
(11, '2025_05_14_114620_create_job_opportunity_offer_state_table', 1),
(12, '2025_05_14_114758_create_job_opportunity_offer_category_table', 1),
(13, '2025_05_14_114918_create_job_opportunity_user_cv_table', 1),
(14, '2025_05_14_115118_create_job_opportunity_work_schedules_table', 1),
(15, '2025_05_14_115135_create_job_opportunity_contract_types_table', 1),
(16, '2025_05_14_115138_create_job_opportunity_offer_table', 1),
(17, '2025_05_14_115139_create_job_opportunity_offer_state_detail_table', 1),
(18, '2025_05_14_115140_create_job_opportunity_applications_table', 1),
(19, '0001_01_01_000000_create_users_table', 2),
(20, '2026_06_29_113000_create_user_notifications_table', 3),
(21, '2026_07_03_120000_add_profile_fields_to_person_table', 4),
(22, '2026_07_08_151641_add_career_to_person_table', 5),
(23, '2026_07_15_000001_create_study_programs_table', 6),
(24, '2026_07_15_000002_add_study_program_id_to_person_table', 7),
(25, '2026_07_15_145010_rename_location_to_modality', 8),
(26, '2026_08_31_231421_create_saved_offers_table', 9),
(27, '2026_09_15_000001_add_egresado_role_to_rol_table', 10),
(28, '2026_10_15_201546_add_banner_image_to_job_opportunity_offer_table', 11),
(29, '2026_10_15_214500_add_cover_image_to_job_opportunity_company_table', 12),
(30, '2026_10_16_174954_add_external_url_to_job_opportunity_offer_table', 13),
(31, '2026_11_04_000001_add_vacancies_to_job_opportunity_offer_table', 14);


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `option`
--

CREATE TABLE `option` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `option_id` bigint(20) UNSIGNED DEFAULT NULL,
  `menu_id` bigint(20) UNSIGNED NOT NULL,
  `name` varchar(255) NOT NULL,
  `name_url` varchar(255) NOT NULL,
  `icon` varchar(255) DEFAULT NULL,
  `is_visible` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


--
-- Volcado de datos para la tabla `option`
--

INSERT INTO `option` (`id`, `option_id`, `menu_id`, `name`, `name_url`, `icon`, `is_visible`, `created_at`, `updated_at`, `deleted_at`) VALUES
(1, NULL, 1, 'Inicio', 'Home', NULL, 1, '2025-06-03 09:58:29', '2025-06-03 09:58:29', NULL),
(2, NULL, 3, 'Ajustes', 'Settings', NULL, 1, '2025-06-03 09:58:29', '2025-06-03 09:58:29', NULL),
(3, NULL, 4, 'Usuarios', 'UsersList', NULL, 1, '2025-06-03 09:58:29', '2025-06-03 09:58:29', NULL),
(4, NULL, 5, 'Mi Perfil', 'Profile', NULL, 1, '2025-06-03 09:58:29', '2025-06-03 09:58:29', NULL),
(5, NULL, 6, 'Convocatorias', 'Offers', NULL, 1, '2025-06-03 09:58:29', '2025-06-03 09:58:29', NULL),
(6, NULL, 6, 'Empresas', 'Companies', NULL, 1, '2025-06-03 09:58:29', '2025-06-03 09:58:29', NULL),
(7, NULL, 6, 'Postulaciones', 'Applications', NULL, 1, '2025-06-03 09:58:29', '2025-06-03 09:58:29', NULL),
(8, NULL, 6, 'Candidato', 'Candidate', NULL, 1, '2025-06-03 09:58:29', '2025-06-03 09:58:29', NULL),
(9, NULL, 6, 'Mantenedores', 'JobMaintainers', NULL, 1, '2025-06-03 09:58:29', '2025-06-03 09:58:29', NULL);


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `password_reset_tokens`
--

CREATE TABLE `password_reset_tokens` (
  `email` varchar(255) NOT NULL,
  `token` varchar(255) NOT NULL,
  `created_at` timestamp NULL DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `person`
--

CREATE TABLE `person` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `document_type` varchar(255) NOT NULL,
  `document_number` varchar(255) NOT NULL,
  `names` varchar(255) NOT NULL,
  `career` varchar(255) DEFAULT NULL,
  `study_program_id` bigint(20) UNSIGNED DEFAULT NULL,
  `phone` varchar(9) NOT NULL,
  `email` varchar(255) NOT NULL,
  `sex` varchar(255) DEFAULT NULL,
  `birth_date` date DEFAULT NULL,
  `native_language` varchar(255) DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL,
  `about_me` text DEFAULT NULL,
  `skills` text DEFAULT NULL,
  `hobbies` text DEFAULT NULL,
  `education` text DEFAULT NULL,
  `experience` text DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `rol`
--

CREATE TABLE `rol` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `name` varchar(255) NOT NULL,
  `key` varchar(255) DEFAULT NULL,
  `level` int(11) NOT NULL DEFAULT 1,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


--
-- Volcado de datos para la tabla `rol`
--

INSERT INTO `rol` (`id`, `name`, `key`, `level`, `created_at`, `updated_at`, `deleted_at`) VALUES
(1, 'ADMINISTRADOR', 'rol_admin', 1, '2025-06-03 09:58:29', '2025-06-03 09:58:29', NULL),
(2, 'DOCENTE', 'rol_teacher', 1, '2025-06-03 09:58:29', '2025-06-03 09:58:29', NULL),
(3, 'ESTUDIANTE', 'rol_student', 1, '2025-06-03 09:58:29', '2025-06-03 09:58:29', NULL),
(4, 'EMPRESA', 'rol_company', 1, '2025-06-03 09:58:29', '2025-06-03 09:58:29', NULL),
(5, 'EGRESADO', 'rol_graduate', 1, '2026-09-15 22:07:27', '2026-09-15 22:07:27', NULL);


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `rol_option`
--

CREATE TABLE `rol_option` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `rol_id` bigint(20) UNSIGNED NOT NULL,
  `option_id` bigint(20) UNSIGNED NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


--
-- Volcado de datos para la tabla `rol_option`
--

INSERT INTO `rol_option` (`id`, `rol_id`, `option_id`) VALUES
(1, 2, 1),
(2, 2, 4),
(3, 3, 1),
(4, 3, 4),
(5, 1, 1),
(6, 1, 2),
(7, 1, 3),
(8, 1, 4),
(9, 1, 6),
(10, 1, 5),
(11, 1, 7),
(12, 4, 1),
(13, 4, 5),
(14, 4, 7),
(15, 1, 9),
(16, 3, 8),
(17, 2, 8);


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `rol_user`
--

CREATE TABLE `rol_user` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `rol_id` bigint(20) UNSIGNED NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `saved_offers`
--

CREATE TABLE `saved_offers` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `offer_id` bigint(20) UNSIGNED NOT NULL,
  `created_at` timestamp NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL,
  `deleted_at` timestamp NULL DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `sessions`
--

CREATE TABLE `sessions` (
  `id` varchar(255) NOT NULL,
  `user_id` bigint(20) UNSIGNED DEFAULT NULL,
  `ip_address` varchar(45) DEFAULT NULL,
  `user_agent` text DEFAULT NULL,
  `payload` longtext NOT NULL,
  `last_activity` int(11) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `study_programs`
--

CREATE TABLE `study_programs` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `name` varchar(255) NOT NULL,
  `is_active` tinyint(1) NOT NULL DEFAULT 1,
  `created_at` timestamp NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `system_configuration`
--

CREATE TABLE `system_configuration` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `key` varchar(255) NOT NULL,
  `name` varchar(255) NOT NULL,
  `type` varchar(255) NOT NULL,
  `value` longtext DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


--
-- Volcado de datos para la tabla `system_configuration`
--

INSERT INTO `system_configuration` (`id`, `key`, `name`, `type`, `value`, `created_at`, `updated_at`, `deleted_at`) VALUES
(1, 'application_name', 'Nombre de la institución', 'string', 'BOLSALABORAL', '2025-06-03 09:58:28', '2026-10-15 20:09:49', NULL),
(2, 'support_emails', 'Correos de soporte', 'array', NULL, '2025-06-03 09:58:28', '2025-06-03 09:58:28', NULL),
(3, 'logo', 'Logo', 'string', NULL, '2025-06-03 09:58:28', '2026-10-15 20:09:19', NULL),
(4, 'favicon', 'Favicon', 'string', NULL, '2025-06-03 09:58:28', '2026-10-15 20:09:19', NULL),
(5, 'banner', 'Banner', 'string', NULL, '2025-06-03 09:58:28', '2026-10-13 22:08:31', NULL),
(6, 'maximum_file_size_to_upload', 'Tamaño máximo de archivos a subir (MB)', 'number', '10', '2025-06-03 09:58:28', '2026-10-15 20:09:49', NULL),
(7, 'extensions_allowed_to_upload', 'Extensiones permitidas para subir archivos', 'array', '[{\"extension\":\"pdf\",\"permitted\":true},{\"extension\":\"doc\",\"permitted\":true},{\"extension\":\"docx\",\"permitted\":true},{\"extension\":\"xls\",\"permitted\":true},{\"extension\":\"xlsx\",\"permitted\":true},{\"extension\":\"ppt\",\"permitted\":true},{\"extension\":\"pptx\",\"permitted\":true},{\"extension\":\"zip\",\"permitted\":true},{\"extension\":\"rar\",\"permitted\":true},{\"extension\":\"jpg\",\"permitted\":true},{\"extension\":\"jpeg\",\"permitted\":true},{\"extension\":\"png\",\"permitted\":true},{\"extension\":\"gif\",\"permitted\":true},{\"extension\":\"mp3\",\"permitted\":true},{\"extension\":\"mp4\",\"permitted\":true},{\"extension\":\"avi\",\"permitted\":true},{\"extension\":\"mkv\",\"permitted\":true}]', '2025-06-03 09:58:28', '2026-10-15 20:09:49', NULL),
(8, 'primary_color', 'Color Primario', 'string', '#01143c', '2025-06-03 09:58:28', '2026-10-15 20:09:49', NULL),
(9, 'primary_container_color', 'Color principal suavizado', 'color', '#b8bdc8', '2026-06-27 21:14:41', '2026-10-15 20:09:49', NULL),
(10, 'secondary_color', 'Color secundario', 'color', '#475eb8', '2026-06-27 21:14:41', '2026-10-15 20:09:49', NULL),
(11, 'secondary_container_color', 'Color secundario suavizado', 'color', '#cbd2eb', '2026-06-27 21:14:41', '2026-10-15 20:09:49', NULL),
(12, 'accent_color', 'Color de acento', 'color', '#ad6e00', '2026-06-27 21:14:41', '2026-10-15 20:09:49', NULL),
(13, 'theme_mode', 'Modo visual', 'select', 'light', '2026-06-27 21:14:41', '2026-10-15 20:09:49', NULL),
(14, 'interface_density', 'Densidad de interfaz', 'select', 'comfortable', '2026-06-27 21:14:41', '2026-10-15 20:09:49', NULL),
(15, 'sidebar_style', 'Estilo del sidebar', 'select', 'expanded', '2026-06-27 21:14:41', '2026-10-15 20:09:49', NULL),
(16, 'allow_student_applications', 'Permitir postulaciones a estudiantes', 'boolean', '1', '2026-09-11 16:15:03', '2026-10-15 20:09:49', NULL),
(17, 'allow_teacher_applications', 'Permitir postulaciones a docentes', 'boolean', '1', '2026-09-11 16:15:03', '2026-10-15 20:09:49', NULL),
(18, 'mail_enabled', 'Habilitar envio de correos', 'boolean', '1', '2026-09-14 22:55:20', '2026-09-16 16:34:03', NULL),
(19, 'mail_host', 'Servidor SMTP', 'text', 'smtp.gmail.com', '2026-09-14 22:55:20', '2026-09-16 16:34:03', NULL),
(20, 'mail_port', 'Puerto SMTP', 'number', '587', '2026-09-14 22:55:20', '2026-09-16 16:34:03', NULL),
(21, 'mail_encryption', 'Seguridad SMTP', 'text', 'tls', '2026-09-14 22:55:20', '2026-09-16 16:34:03', NULL),
(22, 'mail_username', 'Usuario SMTP', 'text', 'conectandoselva@gmail.com', '2026-09-14 22:55:20', '2026-09-16 16:34:03', NULL),
(23, 'mail_from_address', 'Correo remitente', 'text', 'conectandoselva@gmail.com', '2026-09-14 22:55:20', '2026-09-16 16:34:03', NULL),
(24, 'mail_from_name', 'Nombre del remitente', 'text', 'educalatina', '2026-09-14 22:55:20', '2026-09-16 16:34:03', NULL),
(25, 'mail_password', 'Contrasena SMTP', 'password', 'eyJpdiI6IkQzdFVOWG4yK1NGL3R5MWJHU1NQN2c9PSIsInZhbHVlIjoiSFVQQ3N6VW9pK1ZMUWdST2daMWZUWU1mWVozOERvUDZKZkltV3JwTGJxST0iLCJtYWMiOiJlNzEzNGY5MTM1ZmY0NDJkNTQ1NjIzNjZmYTc3ZmVlM2QwZjgyZGJkYmZmMGQzZGU5N2M2YWEwYWYzMzdmNTA1IiwidGFnIjoiIn0=', '2026-09-14 22:55:20', '2026-09-16 16:32:31', NULL),
(26, 'institution_url', 'Portal web institucional', 'url', 'https://iestpvilcanota.edu.pe', NULL, '2026-10-26 21:40:13', NULL);


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `user`
--

CREATE TABLE `user` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `company_id` bigint(20) UNSIGNED DEFAULT NULL,
  `person_id` bigint(20) UNSIGNED DEFAULT NULL,
  `rol_id` bigint(20) UNSIGNED DEFAULT NULL,
  `email` varchar(255) NOT NULL,
  `password` varchar(255) NOT NULL,
  `remember_token` varchar(100) DEFAULT NULL,
  `reset_password_token` varchar(255) DEFAULT NULL,
  `is_active` tinyint(1) NOT NULL DEFAULT 1,
  `last_login` datetime DEFAULT NULL,
  `avatar` text DEFAULT NULL,
  `attempts` smallint(5) UNSIGNED NOT NULL DEFAULT 0,
  `last_attempt` datetime DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci ROW_FORMAT=DYNAMIC;


-- --------------------------------------------------------

--
-- Estructura de tabla para la tabla `user_notifications`
--

CREATE TABLE `user_notifications` (
  `id` bigint(20) UNSIGNED NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `title` varchar(255) NOT NULL,
  `message` text NOT NULL,
  `link` varchar(255) DEFAULT NULL,
  `read_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NULL DEFAULT NULL,
  `updated_at` timestamp NULL DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


--
-- Índices para tablas volcadas
--

--
-- Indices de la tabla `cache`
--
ALTER TABLE `cache`
  ADD PRIMARY KEY (`key`) USING BTREE;


--
-- Indices de la tabla `cache_locks`
--
ALTER TABLE `cache_locks`
  ADD PRIMARY KEY (`key`) USING BTREE;


--
-- Indices de la tabla `failed_jobs`
--
ALTER TABLE `failed_jobs`
  ADD PRIMARY KEY (`id`) USING BTREE,
  ADD UNIQUE KEY `failed_jobs_uuid_unique` (`uuid`) USING BTREE;


--
-- Indices de la tabla `file`
--
ALTER TABLE `file`
  ADD PRIMARY KEY (`id`) USING BTREE,
  ADD KEY `file_fileable_type_fileable_id_index` (`fileable_type`,`fileable_id`) USING BTREE;


--
-- Indices de la tabla `jobs`
--
ALTER TABLE `jobs`
  ADD PRIMARY KEY (`id`) USING BTREE,
  ADD KEY `jobs_queue_index` (`queue`) USING BTREE;


--
-- Indices de la tabla `job_opportunity_applications`
--
ALTER TABLE `job_opportunity_applications`
  ADD PRIMARY KEY (`id`) USING BTREE,
  ADD KEY `job_opportunity_applications_offer_id_foreign` (`offer_id`) USING BTREE,
  ADD KEY `job_opportunity_applications_user_id_foreign` (`user_id`) USING BTREE;


--
-- Indices de la tabla `job_opportunity_company`
--
ALTER TABLE `job_opportunity_company`
  ADD PRIMARY KEY (`id`) USING BTREE,
  ADD UNIQUE KEY `job_opportunity_company_ruc_unique` (`ruc`) USING BTREE;


--
-- Indices de la tabla `job_opportunity_contract_types`
--
ALTER TABLE `job_opportunity_contract_types`
  ADD PRIMARY KEY (`id`) USING BTREE;


--
-- Indices de la tabla `job_opportunity_modalities`
--
ALTER TABLE `job_opportunity_modalities`
  ADD PRIMARY KEY (`id`) USING BTREE;


--
-- Indices de la tabla `job_opportunity_offer`
--
ALTER TABLE `job_opportunity_offer`
  ADD PRIMARY KEY (`id`) USING BTREE,
  ADD UNIQUE KEY `job_opportunity_offer_slug_unique` (`slug`) USING BTREE,
  ADD KEY `job_opportunity_offer_company_id_foreign` (`company_id`) USING BTREE,
  ADD KEY `job_opportunity_offer_location_id_foreign` (`modality_id`) USING BTREE,
  ADD KEY `job_opportunity_offer_state_id_foreign` (`state_id`) USING BTREE,
  ADD KEY `job_opportunity_offer_category_id_foreign` (`category_id`) USING BTREE,
  ADD KEY `job_opportunity_offer_work_schedule_id_foreign` (`work_schedule_id`) USING BTREE,
  ADD KEY `job_opportunity_offer_contract_type_id_foreign` (`contract_type_id`) USING BTREE;


--
-- Indices de la tabla `job_opportunity_offer_category`
--
ALTER TABLE `job_opportunity_offer_category`
  ADD PRIMARY KEY (`id`) USING BTREE;


--
-- Indices de la tabla `job_opportunity_offer_state`
--
ALTER TABLE `job_opportunity_offer_state`
  ADD PRIMARY KEY (`id`) USING BTREE;


--
-- Indices de la tabla `job_opportunity_offer_state_detail`
--
ALTER TABLE `job_opportunity_offer_state_detail`
  ADD PRIMARY KEY (`id`) USING BTREE,
  ADD KEY `job_opportunity_offer_state_detail_offer_id_foreign` (`offer_id`) USING BTREE,
  ADD KEY `job_opportunity_offer_state_detail_state_id_foreign` (`state_id`) USING BTREE;


--
-- Indices de la tabla `job_opportunity_user_cv`
--
ALTER TABLE `job_opportunity_user_cv`
  ADD PRIMARY KEY (`id`) USING BTREE,
  ADD KEY `job_opportunity_user_cv_user_id_foreign` (`user_id`) USING BTREE;


--
-- Indices de la tabla `job_opportunity_work_schedules`
--
ALTER TABLE `job_opportunity_work_schedules`
  ADD PRIMARY KEY (`id`) USING BTREE;


--
-- Indices de la tabla `link`
--
ALTER TABLE `link`
  ADD PRIMARY KEY (`id`) USING BTREE,
  ADD KEY `link_linkable_type_linkable_id_index` (`linkable_type`,`linkable_id`) USING BTREE;


--
-- Indices de la tabla `menu`
--
ALTER TABLE `menu`
  ADD PRIMARY KEY (`id`) USING BTREE;


--
-- Indices de la tabla `migrations`
--
ALTER TABLE `migrations`
  ADD PRIMARY KEY (`id`) USING BTREE;


--
-- Indices de la tabla `option`
--
ALTER TABLE `option`
  ADD PRIMARY KEY (`id`) USING BTREE,
  ADD KEY `option_option_id_foreign` (`option_id`) USING BTREE,
  ADD KEY `option_menu_id_foreign` (`menu_id`) USING BTREE;


--
-- Indices de la tabla `password_reset_tokens`
--
ALTER TABLE `password_reset_tokens`
  ADD PRIMARY KEY (`email`);


--
-- Indices de la tabla `person`
--
ALTER TABLE `person`
  ADD PRIMARY KEY (`id`) USING BTREE,
  ADD KEY `person_study_program_id_foreign` (`study_program_id`);


--
-- Indices de la tabla `rol`
--
ALTER TABLE `rol`
  ADD PRIMARY KEY (`id`) USING BTREE;


--
-- Indices de la tabla `rol_option`
--
ALTER TABLE `rol_option`
  ADD PRIMARY KEY (`id`) USING BTREE,
  ADD KEY `rol_option_rol_id_foreign` (`rol_id`) USING BTREE,
  ADD KEY `rol_option_option_id_foreign` (`option_id`) USING BTREE;


--
-- Indices de la tabla `rol_user`
--
ALTER TABLE `rol_user`
  ADD PRIMARY KEY (`id`) USING BTREE,
  ADD KEY `rol_user_rol_id_foreign` (`rol_id`) USING BTREE,
  ADD KEY `rol_user_user_id_foreign` (`user_id`) USING BTREE;


--
-- Indices de la tabla `saved_offers`
--
ALTER TABLE `saved_offers`
  ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `saved_offers_user_id_offer_id_unique` (`user_id`,`offer_id`),
  ADD KEY `saved_offers_offer_id_foreign` (`offer_id`);


--
-- Indices de la tabla `sessions`
--
ALTER TABLE `sessions`
  ADD PRIMARY KEY (`id`),
  ADD KEY `sessions_user_id_index` (`user_id`),
  ADD KEY `sessions_last_activity_index` (`last_activity`);


--
-- Indices de la tabla `study_programs`
--
ALTER TABLE `study_programs`
  ADD PRIMARY KEY (`id`);


--
-- Indices de la tabla `system_configuration`
--
ALTER TABLE `system_configuration`
  ADD PRIMARY KEY (`id`) USING BTREE,
  ADD UNIQUE KEY `system_configuration_key_unique` (`key`) USING BTREE,
  ADD UNIQUE KEY `system_configuration_name_unique` (`name`) USING BTREE;


--
-- Indices de la tabla `user`
--
ALTER TABLE `user`
  ADD PRIMARY KEY (`id`) USING BTREE,
  ADD UNIQUE KEY `user_email_unique` (`email`) USING BTREE,
  ADD KEY `user_company_id_foreign` (`company_id`) USING BTREE,
  ADD KEY `user_person_id_foreign` (`person_id`) USING BTREE,
  ADD KEY `user_rol_id_foreign` (`rol_id`) USING BTREE;


--
-- Indices de la tabla `user_notifications`
--
ALTER TABLE `user_notifications`
  ADD PRIMARY KEY (`id`),
  ADD KEY `user_notifications_user_id_foreign` (`user_id`);


--
-- AUTO_INCREMENT de las tablas volcadas
--

--
-- AUTO_INCREMENT de la tabla `failed_jobs`
--
ALTER TABLE `failed_jobs`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT;


--
-- AUTO_INCREMENT de la tabla `file`
--
ALTER TABLE `file`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT;


--
-- AUTO_INCREMENT de la tabla `jobs`
--
ALTER TABLE `jobs`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT;


--
-- AUTO_INCREMENT de la tabla `job_opportunity_applications`
--
ALTER TABLE `job_opportunity_applications`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;


--
-- AUTO_INCREMENT de la tabla `job_opportunity_company`
--
ALTER TABLE `job_opportunity_company`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;


--
-- AUTO_INCREMENT de la tabla `job_opportunity_contract_types`
--
ALTER TABLE `job_opportunity_contract_types`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=6;


--
-- AUTO_INCREMENT de la tabla `job_opportunity_modalities`
--
ALTER TABLE `job_opportunity_modalities`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=5;


--
-- AUTO_INCREMENT de la tabla `job_opportunity_offer`
--
ALTER TABLE `job_opportunity_offer`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;


--
-- AUTO_INCREMENT de la tabla `job_opportunity_offer_category`
--
ALTER TABLE `job_opportunity_offer_category`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=7;


--
-- AUTO_INCREMENT de la tabla `job_opportunity_offer_state`
--
ALTER TABLE `job_opportunity_offer_state`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=6;


--
-- AUTO_INCREMENT de la tabla `job_opportunity_offer_state_detail`
--
ALTER TABLE `job_opportunity_offer_state_detail`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;


--
-- AUTO_INCREMENT de la tabla `job_opportunity_user_cv`
--
ALTER TABLE `job_opportunity_user_cv`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;


--
-- AUTO_INCREMENT de la tabla `job_opportunity_work_schedules`
--
ALTER TABLE `job_opportunity_work_schedules`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=6;


--
-- AUTO_INCREMENT de la tabla `link`
--
ALTER TABLE `link`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT;


--
-- AUTO_INCREMENT de la tabla `menu`
--
ALTER TABLE `menu`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=7;


--
-- AUTO_INCREMENT de la tabla `migrations`
--
ALTER TABLE `migrations`
  MODIFY `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=32;


--
-- AUTO_INCREMENT de la tabla `option`
--
ALTER TABLE `option`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=10;


--
-- AUTO_INCREMENT de la tabla `person`
--
ALTER TABLE `person`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;


--
-- AUTO_INCREMENT de la tabla `rol`
--
ALTER TABLE `rol`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=6;


--
-- AUTO_INCREMENT de la tabla `rol_option`
--
ALTER TABLE `rol_option`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=18;


--
-- AUTO_INCREMENT de la tabla `rol_user`
--
ALTER TABLE `rol_user`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;


--
-- AUTO_INCREMENT de la tabla `saved_offers`
--
ALTER TABLE `saved_offers`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;


--
-- AUTO_INCREMENT de la tabla `study_programs`
--
ALTER TABLE `study_programs`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;


--
-- AUTO_INCREMENT de la tabla `system_configuration`
--
ALTER TABLE `system_configuration`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=27;


--
-- AUTO_INCREMENT de la tabla `user`
--
ALTER TABLE `user`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;


--
-- AUTO_INCREMENT de la tabla `user_notifications`
--
ALTER TABLE `user_notifications`
  MODIFY `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=1;


--
-- Restricciones para tablas volcadas
--

--
-- Filtros para la tabla `job_opportunity_applications`
--
ALTER TABLE `job_opportunity_applications`
  ADD CONSTRAINT `job_opportunity_applications_offer_id_foreign` FOREIGN KEY (`offer_id`) REFERENCES `job_opportunity_offer` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `job_opportunity_applications_user_id_foreign` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE ON UPDATE CASCADE;


--
-- Filtros para la tabla `job_opportunity_offer`
--
ALTER TABLE `job_opportunity_offer`
  ADD CONSTRAINT `job_opportunity_offer_category_id_foreign` FOREIGN KEY (`category_id`) REFERENCES `job_opportunity_offer_category` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `job_opportunity_offer_company_id_foreign` FOREIGN KEY (`company_id`) REFERENCES `job_opportunity_company` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `job_opportunity_offer_contract_type_id_foreign` FOREIGN KEY (`contract_type_id`) REFERENCES `job_opportunity_contract_types` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `job_opportunity_offer_modality_id_foreign` FOREIGN KEY (`modality_id`) REFERENCES `job_opportunity_modalities` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `job_opportunity_offer_state_id_foreign` FOREIGN KEY (`state_id`) REFERENCES `job_opportunity_offer_state` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `job_opportunity_offer_work_schedule_id_foreign` FOREIGN KEY (`work_schedule_id`) REFERENCES `job_opportunity_work_schedules` (`id`) ON DELETE CASCADE ON UPDATE CASCADE;


--
-- Filtros para la tabla `job_opportunity_offer_state_detail`
--
ALTER TABLE `job_opportunity_offer_state_detail`
  ADD CONSTRAINT `job_opportunity_offer_state_detail_offer_id_foreign` FOREIGN KEY (`offer_id`) REFERENCES `job_opportunity_offer` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `job_opportunity_offer_state_detail_state_id_foreign` FOREIGN KEY (`state_id`) REFERENCES `job_opportunity_offer_state` (`id`) ON DELETE CASCADE ON UPDATE CASCADE;


--
-- Filtros para la tabla `job_opportunity_user_cv`
--
ALTER TABLE `job_opportunity_user_cv`
  ADD CONSTRAINT `job_opportunity_user_cv_user_id_foreign` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE ON UPDATE CASCADE;


--
-- Filtros para la tabla `option`
--
ALTER TABLE `option`
  ADD CONSTRAINT `option_menu_id_foreign` FOREIGN KEY (`menu_id`) REFERENCES `menu` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `option_option_id_foreign` FOREIGN KEY (`option_id`) REFERENCES `option` (`id`) ON DELETE CASCADE ON UPDATE CASCADE;


--
-- Filtros para la tabla `person`
--
ALTER TABLE `person`
  ADD CONSTRAINT `person_study_program_id_foreign` FOREIGN KEY (`study_program_id`) REFERENCES `study_programs` (`id`) ON DELETE SET NULL;


--
-- Filtros para la tabla `rol_option`
--
ALTER TABLE `rol_option`
  ADD CONSTRAINT `rol_option_option_id_foreign` FOREIGN KEY (`option_id`) REFERENCES `option` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `rol_option_rol_id_foreign` FOREIGN KEY (`rol_id`) REFERENCES `rol` (`id`) ON DELETE CASCADE ON UPDATE CASCADE;


--
-- Filtros para la tabla `rol_user`
--
ALTER TABLE `rol_user`
  ADD CONSTRAINT `rol_user_rol_id_foreign` FOREIGN KEY (`rol_id`) REFERENCES `rol` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `rol_user_user_id_foreign` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE ON UPDATE CASCADE;


--
-- Filtros para la tabla `saved_offers`
--
ALTER TABLE `saved_offers`
  ADD CONSTRAINT `saved_offers_offer_id_foreign` FOREIGN KEY (`offer_id`) REFERENCES `job_opportunity_offer` (`id`) ON DELETE CASCADE,
  ADD CONSTRAINT `saved_offers_user_id_foreign` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE;


--
-- Filtros para la tabla `user`
--
ALTER TABLE `user`
  ADD CONSTRAINT `user_company_id_foreign` FOREIGN KEY (`company_id`) REFERENCES `job_opportunity_company` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `user_person_id_foreign` FOREIGN KEY (`person_id`) REFERENCES `person` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  ADD CONSTRAINT `user_rol_id_foreign` FOREIGN KEY (`rol_id`) REFERENCES `rol` (`id`) ON DELETE CASCADE ON UPDATE CASCADE;


--
-- Filtros para la tabla `user_notifications`
--
ALTER TABLE `user_notifications`
  ADD CONSTRAINT `user_notifications_user_id_foreign` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE;

COMMIT;


/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;

/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;

/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
;
INSERT INTO `migrations` (`id`, `migration`, `batch`) VALUES (32, '0001_01_01_000001_create_cache_table', 99), (33, '0001_01_01_000002_create_jobs_table', 99)