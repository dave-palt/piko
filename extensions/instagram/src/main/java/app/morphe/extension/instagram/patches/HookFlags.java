/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.instagram.patches;

import java.util.Map;
import java.util.HashMap;
import java.util.Set;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.entity.DeveloperOptions;
import app.morphe.extension.instagram.entity.DeveloperOptionsItem;
import app.morphe.extension.instagram.utils.Pref;

public class HookFlags {
    private static Map<String, Boolean> BOOL_FLAGS = new HashMap<>();
    private static DeveloperOptions developerOptions = new DeveloperOptions();

    private static void contactPermissionConsentFlags() {
        BOOL_FLAGS.put("56295", false); //ig_device_permission_consent
    }

    private static void simpleOverflowMenuFlags() {
        BOOL_FLAGS.put("104772", false); //ig_ini
        BOOL_FLAGS.put("117613::0", true); //ig_overflow_menu_icon::use_more_lines_icon
        BOOL_FLAGS.put("100002", true); //ig_igds_android_prism_overflow_sheet
    }
   
    private static void adsFlags() {
//        BOOL_FLAGS.put("58206::0", false); //is_acp_enabled
//        BOOL_FLAGS.put("72396::0", false); //is_mae_exclusion_feed_enabled
//        BOOL_FLAGS.put("78046::0", false); //is_mae_exclusion_feed_enabled
//        BOOL_FLAGS.put("78046::9", false); //enable_no_invalidation_reason_for_mae_exclusion
//        BOOL_FLAGS.put("79181::0", false); //ig_reels_ads_1x2_explore_halc_android::is_enabled
        BOOL_FLAGS.put("110800::0", false); //ig_android_controller_migration::use_v2_controller Removed in version 435.0.0.0.2
        BOOL_FLAGS.put("114983", false); //ig_stories_restyle_midcard
        BOOL_FLAGS.put("95150", false); //ig_stories_music_midcard
        BOOL_FLAGS.put("84366::12", false); //ig_stories_ayt_midcard::enable_add_yours
        BOOL_FLAGS.put("120110", false); //ig_android_scroll_break
        BOOL_FLAGS.put("105778", false); //ig_android_restyle_post_cap_promo_dialog
    }

    // Thanks to @brosssh
    private static void suggestedContentFlags() {
        if (Pref.hideSuggestedContent()) {
            BOOL_FLAGS.put("111509::3", false); //ig_search_ta_nullstate_suggestions::is_android_enabled
            BOOL_FLAGS.put("82771::0", false); //igx_foundation_litho_stories_tray::is_litho_stories_tray_enabled
            BOOL_FLAGS.put("109730", false); //ig_android_ai_discovery_menu
            BOOL_FLAGS.put("80654", false); //ig_meta_ai_cdd_reels_viewer
        }
    }

    // Disables Meta AI surfaces app-wide — ALL meta_ai/genai flags from the
    // 435.0.0.37.76 mobile-config mappings (incl. Gen-AI search summary/serp,
    // discovery, DMs, reels unit, share sheet, comments, feed CDD).
    private static void metaAiFlags() {
        if (!Pref.disableMetaAi()) {
            return;
        }
        BOOL_FLAGS.put("61103", false);   //igd_android_gen_ai_search
        BOOL_FLAGS.put("61303", false);   //ig_android_genai_magic_mod
        BOOL_FLAGS.put("61615", false);   //igd_android_gen_ai_sharing
        BOOL_FLAGS.put("62485", false);   //igd_android_gen_ai_search_xstack
        BOOL_FLAGS.put("69839", false);   //ig_gen_ai_transparency_h1_2024
        BOOL_FLAGS.put("70108", false);   //igd_android_meta_ai_discovery
        BOOL_FLAGS.put("70413", false);   //ig_android_meta_ai_direct
        BOOL_FLAGS.put("70451", false);   //ig_sharing_genai_prototypes
        BOOL_FLAGS.put("71915", false);   //meta_ai_reels_chaining
        BOOL_FLAGS.put("71935", false);   //igd_android_meta_ai_memu_config
        BOOL_FLAGS.put("72411", false);   //meta_ai_discovery_sheet_ui
        BOOL_FLAGS.put("72519", false);   //ig_android_meta_ai_intent_detection
        BOOL_FLAGS.put("73825", false);   //ig_direct_meta_ai_imagine
        BOOL_FLAGS.put("74175", false);   //ig_android_meta_ai_voice
        BOOL_FLAGS.put("74591", false);   //ig4a_genai_creation_general
        BOOL_FLAGS.put("74753", false);   //ig_ads_genai_transparency
        BOOL_FLAGS.put("74792", false);   //fb4a_meta_ai_nux
        BOOL_FLAGS.put("74933", false);   //ig_client_search_meta_ai_integration_new
        BOOL_FLAGS.put("75252", false);   //ig_client_search_meta_ai_hcm_fbigid
        BOOL_FLAGS.put("75278", false);   //ig_android_meta_ai_auto_popup
        BOOL_FLAGS.put("75925", false);   //meta_ai_memu_onboarding
        BOOL_FLAGS.put("76045", false);   //ig_meta_ai_imagine_intent
        BOOL_FLAGS.put("76479", false);   //igd_metaai_composer_entrypoint
        BOOL_FLAGS.put("76608", false);   //ig_android_sharing_genai_expander
        BOOL_FLAGS.put("77182", false);   //ig_rtc_gen_ai_backgrounds
        BOOL_FLAGS.put("77281", false);   //ig_meta_ai_assets
        BOOL_FLAGS.put("77485", false);   //ig_meta_ai_assets_sessionless
        BOOL_FLAGS.put("77572", false);   //ig_meta_ai_profile_context_menu_enabled
        BOOL_FLAGS.put("77870", false);   //ig_meta_ai_sharing_improvements_mc
        BOOL_FLAGS.put("78149", false);   //igd_android_thread_meta_ai_improvements
        BOOL_FLAGS.put("78355", false);   //ig_metaai_prompt_sheet
        BOOL_FLAGS.put("78669", false);   //igd_android_genai_new_messages_summary
        BOOL_FLAGS.put("78699", false);   //meta_ai_ig_no_space_invocation
        BOOL_FLAGS.put("78970", false);   //ig_meta_ai_cdd_comments_sheet
        BOOL_FLAGS.put("79140", false);   //igd_gen_ai_craft_h2_2024
        BOOL_FLAGS.put("79677", false);   //igd_meta_ai_preemptive_prefetch
        BOOL_FLAGS.put("79859", false);   //ig_android_mai_imagine
        BOOL_FLAGS.put("80171", false);   //odin_ig_android_metaai_ner
        BOOL_FLAGS.put("80172", false);   //odin_ig_android_metaai_integrity
        BOOL_FLAGS.put("80654", false);   //ig_meta_ai_cdd_reels_viewer
        BOOL_FLAGS.put("80730", false);   //meta_ai_android_ig_intent_nux_key
        BOOL_FLAGS.put("82374", false);   //ig_android_genai_ai_filter
        BOOL_FLAGS.put("83278", false);   //meta_ai_media_share_sheet
        BOOL_FLAGS.put("83354", false);   //xstack_overlayconfig_metaaivoicestateconfig
        BOOL_FLAGS.put("84760", false);   //ig_meta_ai_cdd_feed
        BOOL_FLAGS.put("85119", false);   //genai_unified_response_ig_android
        BOOL_FLAGS.put("85865", false);   //metaai_igd_android_deeplink
        BOOL_FLAGS.put("86062", false);   //ig_android_meta_ai_imagine_command_expansion
        BOOL_FLAGS.put("86804", false);   //xstack_overlayconfig_metaaidataconnectstateconfig
        BOOL_FLAGS.put("86926", false);   //igd_meta_ai_dr_suggested_prompts_fnf_thread
        BOOL_FLAGS.put("87075", false);   //ig_meta_ai_intent_consent_pj
        BOOL_FLAGS.put("87528", false);   //igd_meta_ai_style_edits
        BOOL_FLAGS.put("88642", false);   //ig_android_genai_nux
        BOOL_FLAGS.put("89456", false);   //xstack_overlayconfig_metaaiturnstatsconfig
        BOOL_FLAGS.put("91489", false);   //igd_meta_ai_overflow_context_menu
        BOOL_FLAGS.put("95528", false);   //ig_meta_ai_cdd_async_fetch_android
        BOOL_FLAGS.put("96235", false);   //wearables_ig_meta_ai_entrypoints
        BOOL_FLAGS.put("96543", false);   //igd_meta_ai_celebration_intent
        BOOL_FLAGS.put("97010", false);   //igs2_meta_ai_app_top_position
        BOOL_FLAGS.put("97473", false);   //meta_ai_ig_location_ai_summary
        BOOL_FLAGS.put("97608", false);   //ig_meta_ai_header_attribution
        BOOL_FLAGS.put("99726", false);   //ig_settings_2_original_audio_reuse_on_meta_ai
        BOOL_FLAGS.put("99882", false);   //ig_android_original_audio_reuse_on_meta_ai
        BOOL_FLAGS.put("101608", false);  //meta_ai_voice_crash_fix
        BOOL_FLAGS.put("104879", false);  //igd_meta_ai_thread_banner
        BOOL_FLAGS.put("108777", false);  //ig_android_genai_remix
        BOOL_FLAGS.put("108885", false);  //ig_android_genai_kinetic_sand
        BOOL_FLAGS.put("108911", false);  //ig_android_meta_ai_v2
        BOOL_FLAGS.put("110316", false);  //ig_meta_ai_mentions
        BOOL_FLAGS.put("110503", false);  //ig_android_lead_gen_ai_incentive_launcher
        BOOL_FLAGS.put("110689", false);  //ig_ads_android_story_genai_question_card
        BOOL_FLAGS.put("112266", false);  //xstack_overlayconfig_metaaisessioninfoconfig
        BOOL_FLAGS.put("112752", false);  //ig_android_genai_rate_limiting
        BOOL_FLAGS.put("113499", false);  //ig_mai_app_growth_notifications
        BOOL_FLAGS.put("114218", false);  //ig_genai_magic_mod_enable_xposting
        BOOL_FLAGS.put("115193", false);  //gen_ai_suggested_reply_android
        BOOL_FLAGS.put("116406", false);  //p92_android_meta_ai_reply_bot
        BOOL_FLAGS.put("116639", false);  //xstack_overlayconfig_metaaiinternaldebugv2config
        BOOL_FLAGS.put("118403", false);  //igd_meta_ai_feedback_rail
        BOOL_FLAGS.put("118665", false);  //xstack_overlayconfig_metaaiacpdatachannelconfig
        BOOL_FLAGS.put("119736", false);  //ig_search_meta_ai_upsell
        BOOL_FLAGS.put("120893", false);  //ig_meta_ai_in_reels_stories_unit
        BOOL_FLAGS.put("121262", false);  //ig_search_android_serp_meta_ai_thread_fixes
        BOOL_FLAGS.put("121836", false);  //igd_meta_ai_upsell
        BOOL_FLAGS.put("122162", false);  //ig_android_meta_ai_2_share_sheet
        BOOL_FLAGS.put("122384", false);  //ig_android_app_ads_mai_end_card_craft_update
        BOOL_FLAGS.put("124136", false);  //ig_android_genai_music_in_feed_ads
        BOOL_FLAGS.put("124353", false);  //bcn_android_meta_ai_dm
        BOOL_FLAGS.put("125961", false);  //ig_android_meta_ai_voice_2_0
    }

    private static void profileActionBarFlags() {
        Set<String> pref = Pref.userProfileActionBarButtons();
        if(!pref.isEmpty()) {
            BOOL_FLAGS.put("81826::0", true); //igx_action_bar_service_replacement::is_profile_replaced
            BOOL_FLAGS.put("89230::0", true); //ig_android_profile_overflow_menu_redesign_launcher:enabled
        }
    }

    private static void mainFeedActionBarFlags() {
        Set<String> pref = Pref.mainFeedActionBarButtons();
        if(!pref.isEmpty()) {
            BOOL_FLAGS.put("81826::1", true); //igx_action_bar_service_replacement::is_main_feed_replaced
            BOOL_FLAGS.put("81826::4", true); //igx_action_bar_service_replacement::is_main_feed_large_screen_replaced
        }
    }

    private static void employeeOptionsFlags() {
        if(Pref.enableEmployeeOptions()){
            BOOL_FLAGS.put("28538::0", true); //ig_android_employee_options::is_enabled
        }else{
            BOOL_FLAGS.put("28538::0", false); //ig_android_employee_options::is_enabled
        }
    }

    public static void load() {
    }

    public static Boolean handleBoolFlags(long mobileConfigSpecifier) {
        try {
            DeveloperOptionsItem developerOptionsItem = new DeveloperOptionsItem(mobileConfigSpecifier);
            // Sometimes I want to block all the subflags inside a universal ID.
            // In which case I would only add the universal ID in the BOOL_MAP map.
            // If a boolean value is found then it will return else it will check for for the usual config ID
            String universalId = developerOptionsItem.getUniversalId();
            Boolean universalFlag = BOOL_FLAGS.getOrDefault(universalId, null);
            if(universalFlag!=null) return universalFlag;

            String configId = developerOptionsItem.getConfigId();
            return BOOL_FLAGS.getOrDefault(configId, null);
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
        return null;
    }

}
