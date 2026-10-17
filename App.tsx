import React, {useEffect, useRef, useState} from 'react';
import {
  ActivityIndicator,
  FlatList,
  KeyboardAvoidingView,
  Modal,
  NativeEventEmitter,
  NativeModules,
  Platform,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';

type ChatMessage = {
  id: number;
  role: 'user' | 'assistant';
  content: string;
  thinkingContent: string;
  isStreaming: boolean;
  isThinking: boolean;
  showThinking: boolean;
};

type InferenceSettings = {
  nCtx: number;
  nThreads: number;
  nThreadsBatch: number;
  flashAttn: boolean;
  effectiveFlashAttn: boolean;
  kvTypeK: number;
  kvTypeV: number;
  kvTypeKLabel: string;
  kvTypeVLabel: string;
  useMmap: boolean;
  useMlock: boolean;
  reuseCache: boolean;
};

type GenerationStats = {
  reusedTokens: number;
  promptTokens: number;
  generatedTokens: number;
  promptMs: number;
  generationMs: number;
  genTokensPerSecond: number;
} | null;

type NativeState = {
  messages: ChatMessage[];
  modelStatus: 'notLoaded' | 'loading' | 'loaded' | 'error';
  modelName: string;
  modelError: string;
  isGenerating: boolean;
  generationState: string;
  importing: boolean;
  importName: string;
  settings: InferenceSettings;
  stats: GenerationStats;
};

const bridge = NativeModules.LocalAI;
const emitter = new NativeEventEmitter(bridge);
const KV_OPTIONS = [
  {code: 0, label: 'F16'},
  {code: 1, label: 'Q8_0'},
  {code: 2, label: 'Q5_1'},
  {code: 3, label: 'Q5_0'},
  {code: 4, label: 'Q4_1'},
  {code: 5, label: 'Q4_0'},
];

const DEFAULT_SETTINGS: InferenceSettings = {
  nCtx: 4096,
  nThreads: 4,
  nThreadsBatch: 4,
  flashAttn: false,
  effectiveFlashAttn: false,
  kvTypeK: 0,
  kvTypeV: 0,
  kvTypeKLabel: 'F16',
  kvTypeVLabel: 'F16',
  useMmap: true,
  useMlock: false,
  reuseCache: true,
};

const DEFAULT_STATE: NativeState = {
  messages: [],
  modelStatus: 'notLoaded',
  modelName: '',
  modelError: '',
  isGenerating: false,
  generationState: 'idle',
  importing: false,
  importName: '',
  settings: DEFAULT_SETTINGS,
  stats: null,
};

export default function App() {
  const [state, setState] = useState<NativeState>(DEFAULT_STATE);
  const [draft, setDraft] = useState('');
  const [notice, setNotice] = useState('');
  const [settingsOpen, setSettingsOpen] = useState(false);
  const listRef = useRef<FlatList<ChatMessage>>(null);

  useEffect(() => {
    const stateSubscription = emitter.addListener('LocalAIState', (next: NativeState) => {
      setState(next);
    });
    const noticeSubscription = emitter.addListener(
      'LocalAINotice',
      (event: {message?: string}) => setNotice(event.message || ''),
    );

    bridge.getInitialState()
      .then((initial: NativeState) => setState(initial))
      .catch((error: unknown) => {
        const message = error instanceof Error ? error.message : 'Nao foi possivel iniciar o app.';
        setNotice(message);
      });

    return () => {
      stateSubscription.remove();
      noticeSubscription.remove();
    };
  }, []);

  const modelReady = state.modelStatus === 'loaded';
  const modelLabel = modelReady
    ? state.modelName
    : state.modelStatus === 'loading'
      ? 'Carregando modelo...'
      : state.modelStatus === 'error'
        ? 'Falha ao carregar'
        : 'Nenhum modelo';

  const statusDetails = modelReady
    ? 'ctx ' + state.settings.nCtx +
      ' · KV ' + state.settings.kvTypeKLabel + '/' + state.settings.kvTypeVLabel +
      ' · ' + state.settings.nThreads + ' threads' +
      (state.stats && state.stats.genTokensPerSecond > 0
        ? ' · ' + state.stats.genTokensPerSecond.toFixed(1) + ' tok/s'
        : '')
    : 'Inferencia e historico ficam no proprio aparelho';

  const send = () => {
    const text = draft.trim();
    if (!text || !modelReady || state.isGenerating) {
      return;
    }
    setDraft('');
    bridge.sendMessage(text);
  };

  const renderMessage = ({item}: {item: ChatMessage}) => {
    const isUser = item.role === 'user';
    return (
      <View style={[styles.messageRow, isUser ? styles.userRow : styles.assistantRow]}>
        <View style={[styles.avatar, isUser ? styles.userAvatar : styles.assistantAvatar]}>
          <Text style={styles.avatarText}>{isUser ? 'VOCE' : 'AI'}</Text>
        </View>
        <View style={[styles.messageCard, isUser ? styles.userCard : styles.assistantCard]}>
          <Text style={styles.messageLabel}>{isUser ? 'Voce' : 'Local AI'}</Text>
          {!!item.thinkingContent && (
            <Pressable
              style={styles.thinkingToggle}
              onPress={() => bridge.toggleThinking(item.id)}>
              <Text style={styles.thinkingLabel}>
                {item.isThinking ? 'Raciocinando...' : item.showThinking ? 'Ocultar raciocinio' : 'Ver raciocinio'}
              </Text>
            </Pressable>
          )}
          {!!item.thinkingContent && item.showThinking && (
            <Text style={styles.thinkingText}>{item.thinkingContent}</Text>
          )}
          {!!item.content && <Text selectable style={styles.messageText}>{item.content}</Text>}
          {item.isStreaming && !item.content && !item.thinkingContent && (
            <ActivityIndicator size="small" color="#A5B4FC" />
          )}
        </View>
      </View>
    );
  };

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.safeArea} edges={['top', 'bottom']}>
        <StatusBar barStyle="light-content" />
        <KeyboardAvoidingView
          style={styles.screen}
          behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
          <View style={styles.header}>
            <View style={styles.brandMark}><Text style={styles.brandMarkText}>✦</Text></View>
            <View style={styles.titleBlock}>
              <Text style={styles.title}>Local AI</Text>
              <Text style={styles.subtitle}>Seu assistente, no seu dispositivo</Text>
            </View>
            <Pressable style={styles.iconButton} onPress={() => setSettingsOpen(true)}>
              <Text style={styles.iconButtonText}>⚙</Text>
            </Pressable>
            <Pressable style={styles.iconButton} onPress={() => bridge.clearChat()}>
              <Text style={styles.iconButtonText}>⌫</Text>
            </Pressable>
          </View>

          <Pressable
            style={styles.modelBar}
            onPress={() => bridge.pickModel()}
            disabled={state.importing || state.modelStatus === 'loading'}>
            <View style={styles.statusDotWrap}>
              <View style={[styles.statusDot, modelReady ? styles.dotReady : styles.dotIdle]} />
            </View>
            <View style={styles.modelTextBlock}>
              <Text numberOfLines={1} style={styles.modelName}>{modelLabel}</Text>
              <Text numberOfLines={1} style={styles.modelDetails}>{statusDetails}</Text>
            </View>
            {state.importing || state.modelStatus === 'loading'
              ? <ActivityIndicator size="small" color="#A5B4FC" />
              : <Text style={styles.modelAction}>Abrir GGUF  ↗</Text>}
          </Pressable>

          {!!notice && (
            <Pressable style={styles.notice} onPress={() => setNotice('')}>
              <Text style={styles.noticeText}>{notice}  ×</Text>
            </Pressable>
          )}

          {state.importing && (
            <View style={styles.importingRow}>
              <ActivityIndicator size="small" color="#A5B4FC" />
              <Text style={styles.importingText}>Importando {state.importName}...</Text>
            </View>
          )}

          {!modelReady && state.messages.length === 0 ? (
            <View style={styles.emptyState}>
              <View style={styles.heroIcon}><Text style={styles.heroIconText}>✦</Text></View>
              <Text style={styles.heroTitle}>IA local. Seus dados, seus termos.</Text>
              <Text style={styles.heroDescription}>
                Selecione um modelo GGUF para conversar offline. O motor llama.cpp e o historico permanecem no Android.
              </Text>
              <Pressable style={styles.primaryButton} onPress={() => bridge.pickModel()}>
                <Text style={styles.primaryButtonText}>Selecionar modelo GGUF</Text>
              </Pressable>
              <View style={styles.privacyPill}>
                <View style={[styles.statusDot, styles.dotReady]} />
                <Text style={styles.privacyText}>Sem conta · Sem nuvem · No aparelho</Text>
              </View>
              {state.modelStatus === 'error' && (
                <Text style={styles.errorText}>{state.modelError}</Text>
              )}
            </View>
          ) : (
            <FlatList
              ref={listRef}
              data={state.messages}
              renderItem={renderMessage}
              keyExtractor={(item) => String(item.id)}
              style={styles.messageList}
              contentContainerStyle={styles.messageContent}
              keyboardShouldPersistTaps="handled"
              onContentSizeChange={() => listRef.current?.scrollToEnd({animated: true})}
              ListEmptyComponent={
                <View style={styles.chatEmpty}>
                  <Text style={styles.chatEmptyTitle}>Modelo pronto para conversar</Text>
                  <Text style={styles.chatEmptyText}>Mande uma mensagem para comecar.</Text>
                </View>
              }
            />
          )}

          <View style={styles.composerArea}>
            {!modelReady && (
              <Text style={styles.composerHint}>Carregue um modelo para habilitar o chat.</Text>
            )}
            <View style={styles.composer}>
              <TextInput
                style={styles.input}
                value={draft}
                onChangeText={setDraft}
                placeholder={modelReady ? 'Pergunte alguma coisa...' : 'Carregue um modelo GGUF primeiro'}
                placeholderTextColor="#72798B"
                multiline
                maxLength={12000}
                editable={modelReady && !state.isGenerating}
                onSubmitEditing={send}
                returnKeyType="send"
              />
              <Pressable
                style={[styles.sendButton, (!modelReady || (!state.isGenerating && !draft.trim())) && styles.buttonDisabled]}
                onPress={() => state.isGenerating ? bridge.stopGeneration() : send()}
                disabled={!state.isGenerating && (!modelReady || !draft.trim())}>
                <Text style={styles.sendButtonText}>{state.isGenerating ? '■' : '↑'}</Text>
              </Pressable>
            </View>
            <Text style={styles.footerHint}>As respostas sao geradas no aparelho e podem levar alguns segundos.</Text>
          </View>
        </KeyboardAvoidingView>

        <SettingsModal
          visible={settingsOpen}
          current={state.settings}
          onClose={() => setSettingsOpen(false)}
          onApply={(next) => {
            bridge.applySettings(next);
            setSettingsOpen(false);
          }}
          onUnload={() => {
            bridge.unloadModel();
            setSettingsOpen(false);
          }}
        />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

function SettingsModal({
  visible,
  current,
  onClose,
  onApply,
  onUnload,
}: {
  visible: boolean;
  current: InferenceSettings;
  onClose: () => void;
  onApply: (settings: InferenceSettings) => void;
  onUnload: () => void;
}) {
  const [draft, setDraft] = useState<InferenceSettings>(current);

  useEffect(() => {
    if (visible) {
      setDraft(current);
    }
  }, [visible]);

  const cycleKv = (key: 'kvTypeK' | 'kvTypeV') => {
    const index = KV_OPTIONS.findIndex((entry) => entry.code === draft[key]);
    const next = KV_OPTIONS[((index + 1) % KV_OPTIONS.length)] || KV_OPTIONS[0];
    setDraft({...draft, [key]: next.code});
  };

  const toggle = (key: 'flashAttn' | 'useMmap' | 'useMlock' | 'reuseCache') => {
    setDraft({...draft, [key]: !draft[key]});
  };

  return (
    <Modal visible={visible} animationType="slide" transparent onRequestClose={onClose}>
      <View style={styles.modalScrim}>
        <View style={styles.modalCard}>
          <View style={styles.modalHeader}>
            <View>
              <Text style={styles.modalTitle}>Desempenho</Text>
              <Text style={styles.modalSubtitle}>Parametros do motor local</Text>
            </View>
            <Pressable onPress={onClose} style={styles.iconButton}>
              <Text style={styles.iconButtonText}>×</Text>
            </Pressable>
          </View>
          <ScrollView contentContainerStyle={styles.settingsContent}>
            <Text style={styles.settingLabel}>Contexto</Text>
            <View style={styles.choiceRow}>
              {[2048, 4096, 8192].map((size) => (
                <Pressable
                  key={size}
                  onPress={() => setDraft({...draft, nCtx: size})}
                  style={[styles.choice, draft.nCtx === size && styles.choiceSelected]}>
                  <Text style={[styles.choiceText, draft.nCtx === size && styles.choiceTextSelected]}>{size}</Text>
                </Pressable>
              ))}
            </View>

            <Text style={styles.settingLabel}>Threads de geracao</Text>
            <View style={styles.stepper}>
              <Pressable style={styles.choice} onPress={() => setDraft({...draft, nThreads: Math.max(1, draft.nThreads - 1)})}>
                <Text style={styles.choiceText}>−</Text>
              </Pressable>
              <Text style={styles.stepperValue}>{draft.nThreads}</Text>
              <Pressable style={styles.choice} onPress={() => setDraft({...draft, nThreads: Math.min(32, draft.nThreads + 1)})}>
                <Text style={styles.choiceText}>+</Text>
              </Pressable>
            </View>

            <Text style={styles.settingLabel}>KV cache (toque para alternar)</Text>
            <View style={styles.choiceRow}>
              <Pressable style={styles.choice} onPress={() => cycleKv('kvTypeK')}>
                <Text style={styles.choiceText}>K: {KV_OPTIONS.find((item) => item.code === draft.kvTypeK)?.label || 'F16'}</Text>
              </Pressable>
              <Pressable style={styles.choice} onPress={() => cycleKv('kvTypeV')}>
                <Text style={styles.choiceText}>V: {KV_OPTIONS.find((item) => item.code === draft.kvTypeV)?.label || 'F16'}</Text>
              </Pressable>
            </View>

            <SettingToggle label="Flash attention" value={draft.flashAttn} onPress={() => toggle('flashAttn')} />
            <SettingToggle label="Usar mmap" value={draft.useMmap} onPress={() => toggle('useMmap')} />
            <SettingToggle label="Bloquear memoria (mlock)" value={draft.useMlock} onPress={() => toggle('useMlock')} />
            <SettingToggle label="Reutilizar cache entre mensagens" value={draft.reuseCache} onPress={() => toggle('reuseCache')} />
            <Text style={styles.settingNote}>
              KV V quantizado ativa flash attention automaticamente. Alterar estas opcoes recarrega o modelo e pode consumir mais memoria.
            </Text>
          </ScrollView>
          <View style={styles.modalActions}>
            <Pressable style={styles.secondaryButton} onPress={onUnload}>
              <Text style={styles.secondaryButtonText}>Descarregar modelo</Text>
            </Pressable>
            <Pressable style={styles.primaryButtonCompact} onPress={() => onApply(draft)}>
              <Text style={styles.primaryButtonText}>Aplicar</Text>
            </Pressable>
          </View>
        </View>
      </View>
    </Modal>
  );
}

function SettingToggle({label, value, onPress}: {label: string; value: boolean; onPress: () => void}) {
  return (
    <Pressable style={styles.toggleRow} onPress={onPress}>
      <Text style={styles.toggleLabel}>{label}</Text>
      <View style={[styles.toggle, value && styles.toggleOn]}>
        <View style={[styles.toggleThumb, value && styles.toggleThumbOn]} />
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  safeArea: {flex: 1, backgroundColor: '#090B10'},
  screen: {flex: 1, backgroundColor: '#090B10'},
  header: {minHeight: 68, paddingHorizontal: 16, flexDirection: 'row', alignItems: 'center', gap: 10, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: '#242936'},
  brandMark: {width: 38, height: 38, borderRadius: 14, backgroundColor: '#252746', alignItems: 'center', justifyContent: 'center'},
  brandMarkText: {fontSize: 24, color: '#B9B7FF'},
  titleBlock: {flex: 1},
  title: {fontSize: 18, fontWeight: '700', color: '#F2F3FA'},
  subtitle: {fontSize: 11, color: '#858DA0', marginTop: 2},
  iconButton: {width: 38, height: 38, borderRadius: 13, backgroundColor: '#171B25', alignItems: 'center', justifyContent: 'center', borderWidth: 1, borderColor: '#292E3B'},
  iconButtonText: {fontSize: 19, color: '#C2C7D4'},
  modelBar: {marginHorizontal: 14, marginTop: 12, padding: 12, borderRadius: 16, borderColor: '#2B3040', borderWidth: 1, backgroundColor: '#11151E', flexDirection: 'row', alignItems: 'center', gap: 10},
  statusDotWrap: {width: 12, alignItems: 'center'},
  statusDot: {width: 7, height: 7, borderRadius: 4},
  dotReady: {backgroundColor: '#57D6A0'},
  dotIdle: {backgroundColor: '#8A91A5'},
  modelTextBlock: {flex: 1},
  modelName: {fontSize: 13, fontWeight: '600', color: '#E9EBF4'},
  modelDetails: {fontSize: 10, color: '#8A92A5', marginTop: 4},
  modelAction: {fontSize: 11, fontWeight: '600', color: '#B9B7FF'},
  notice: {marginHorizontal: 14, marginTop: 8, padding: 10, borderRadius: 10, backgroundColor: '#38252A'},
  noticeText: {fontSize: 12, color: '#FFD0D6'},
  importingRow: {paddingHorizontal: 18, paddingTop: 10, flexDirection: 'row', alignItems: 'center', gap: 8},
  importingText: {fontSize: 12, color: '#A5B4FC'},
  emptyState: {flex: 1, paddingHorizontal: 28, alignItems: 'center', justifyContent: 'center'},
  heroIcon: {width: 72, height: 72, borderRadius: 26, alignItems: 'center', justifyContent: 'center', backgroundColor: '#232643', borderWidth: 1, borderColor: '#3E4166', marginBottom: 22},
  heroIconText: {fontSize: 37, color: '#C4C1FF'},
  heroTitle: {fontSize: 22, fontWeight: '700', color: '#F0F1F8', textAlign: 'center', lineHeight: 30},
  heroDescription: {fontSize: 14, lineHeight: 22, color: '#949BAE', textAlign: 'center', marginTop: 12, marginBottom: 24},
  primaryButton: {minHeight: 48, paddingHorizontal: 18, borderRadius: 15, backgroundColor: '#7775EE', alignItems: 'center', justifyContent: 'center'},
  primaryButtonCompact: {minHeight: 44, paddingHorizontal: 22, borderRadius: 13, backgroundColor: '#7775EE', alignItems: 'center', justifyContent: 'center'},
  primaryButtonText: {fontSize: 13, fontWeight: '700', color: '#FFFFFF'},
  privacyPill: {marginTop: 18, flexDirection: 'row', alignItems: 'center', gap: 8, paddingVertical: 8, paddingHorizontal: 11, borderRadius: 99, backgroundColor: '#121820'},
  privacyText: {fontSize: 11, color: '#8E97A7'},
  errorText: {fontSize: 12, color: '#FF9CA7', marginTop: 14, textAlign: 'center'},
  messageList: {flex: 1},
  messageContent: {paddingHorizontal: 14, paddingVertical: 18, gap: 16},
  messageRow: {flexDirection: 'row', alignItems: 'flex-start', gap: 9},
  userRow: {justifyContent: 'flex-end'},
  assistantRow: {justifyContent: 'flex-start'},
  avatar: {width: 30, height: 30, borderRadius: 11, alignItems: 'center', justifyContent: 'center', marginTop: 2},
  userAvatar: {backgroundColor: '#34334F'},
  assistantAvatar: {backgroundColor: '#193A31'},
  avatarText: {fontSize: 8, fontWeight: '800', color: '#DADCF6'},
  messageCard: {maxWidth: '88%', padding: 13, borderRadius: 16, borderWidth: 1},
  userCard: {backgroundColor: '#292B46', borderColor: '#3B3D5D', borderTopRightRadius: 5},
  assistantCard: {backgroundColor: '#141821', borderColor: '#282E3B', borderTopLeftRadius: 5},
  messageLabel: {fontSize: 10, letterSpacing: 0.4, fontWeight: '700', color: '#9EA5B7', marginBottom: 7},
  messageText: {fontSize: 14, lineHeight: 22, color: '#EDF0F7'},
  thinkingToggle: {alignSelf: 'flex-start', marginBottom: 7, paddingVertical: 5, paddingHorizontal: 8, borderRadius: 8, backgroundColor: '#24293A'},
  thinkingLabel: {fontSize: 11, color: '#B9B7FF'},
  thinkingText: {fontSize: 12, lineHeight: 18, color: '#9EA5B9', paddingLeft: 9, marginBottom: 8, borderLeftWidth: 2, borderLeftColor: '#6866BD'},
  chatEmpty: {paddingTop: 70, alignItems: 'center'},
  chatEmptyTitle: {fontSize: 17, fontWeight: '700', color: '#ECEEF8'},
  chatEmptyText: {fontSize: 13, color: '#8F97AA', marginTop: 8},
  composerArea: {borderTopWidth: 1, borderTopColor: '#242936', paddingHorizontal: 13, paddingTop: 12, paddingBottom: 10, backgroundColor: '#0C0F16'},
  composerHint: {fontSize: 11, color: '#8E96AA', marginBottom: 7, marginLeft: 4},
  composer: {minHeight: 52, maxHeight: 132, flexDirection: 'row', alignItems: 'flex-end', padding: 6, gap: 8, borderRadius: 17, backgroundColor: '#171B25', borderColor: '#2B3040', borderWidth: 1},
  input: {flex: 1, maxHeight: 112, paddingHorizontal: 10, paddingVertical: 8, color: '#F2F3F9', fontSize: 14, lineHeight: 20},
  sendButton: {width: 38, height: 38, borderRadius: 13, backgroundColor: '#7775EE', alignItems: 'center', justifyContent: 'center'},
  buttonDisabled: {opacity: 0.35},
  sendButtonText: {fontSize: 20, fontWeight: '800', color: '#FFFFFF'},
  footerHint: {fontSize: 10, color: '#666E81', textAlign: 'center', marginTop: 8},
  modalScrim: {flex: 1, justifyContent: 'flex-end', backgroundColor: 'rgba(0,0,0,0.62)'},
  modalCard: {maxHeight: '92%', paddingTop: 20, paddingHorizontal: 18, paddingBottom: 18, backgroundColor: '#11151F', borderTopLeftRadius: 25, borderTopRightRadius: 25, borderWidth: 1, borderColor: '#2A3040'},
  modalHeader: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14},
  modalTitle: {fontSize: 21, fontWeight: '800', color: '#F2F3FB'},
  modalSubtitle: {fontSize: 12, color: '#8992A7', marginTop: 4},
  settingsContent: {paddingBottom: 12, gap: 10},
  settingLabel: {fontSize: 12, fontWeight: '700', color: '#C4C9D7', marginTop: 10},
  choiceRow: {flexDirection: 'row', alignItems: 'center', gap: 9, flexWrap: 'wrap'},
  choice: {minHeight: 40, minWidth: 58, paddingHorizontal: 12, borderRadius: 11, borderWidth: 1, borderColor: '#303648', backgroundColor: '#191E2A', alignItems: 'center', justifyContent: 'center'},
  choiceSelected: {borderColor: '#7775EE', backgroundColor: '#2A294D'},
  choiceText: {fontSize: 12, fontWeight: '600', color: '#AEB5C7'},
  choiceTextSelected: {color: '#D7D5FF'},
  stepper: {flexDirection: 'row', alignItems: 'center', gap: 20},
  stepperValue: {fontSize: 16, fontWeight: '700', color: '#F2F3FB', minWidth: 24, textAlign: 'center'},
  toggleRow: {minHeight: 45, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: '#292E3C'},
  toggleLabel: {fontSize: 13, color: '#D5D9E5', flex: 1, paddingRight: 10},
  toggle: {width: 43, height: 25, borderRadius: 14, backgroundColor: '#333848', justifyContent: 'center', padding: 3},
  toggleOn: {backgroundColor: '#605DC4'},
  toggleThumb: {width: 19, height: 19, borderRadius: 10, backgroundColor: '#AEB5C6'},
  toggleThumbOn: {alignSelf: 'flex-end', backgroundColor: '#FFFFFF'},
  settingNote: {fontSize: 11, lineHeight: 17, color: '#8992A7', marginTop: 8},
  modalActions: {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 10, paddingTop: 10},
  secondaryButton: {minHeight: 44, paddingHorizontal: 12, borderRadius: 13, borderWidth: 1, borderColor: '#42485A', alignItems: 'center', justifyContent: 'center'},
  secondaryButtonText: {fontSize: 11, fontWeight: '600', color: '#C1C6D5'},
});
