from collections import OrderedDict
from typing import Dict, List
from overrides import overrides
from keras.layers import Input

from ...data.instances.sentence_selection import SentenceSelectionInstance
from ...training.losses import ranking_loss, ranking_loss_with_margin
from ...layers.attention import Attention
from ...layers.backend import ReplaceMaskedValues, CollapseToBatch, ExpandFromBatch
from ...layers.wrappers import AddEncoderMask
from ...tensors.backend import VERY_NEGATIVE_NUMBER
from ...training import TextTrainer
from ...training.models import DeepQaModel
from ...common.params import Params


class SiameseSentenceSelector(TextTrainer):
    """
    This class implements a (generally) Siamese network for the answer
    sentence selectiont ask. Given a question and a collection of sentences,
    we aim to identify which sentence has the answer to the question. This
    model encodes the question and each sentence with (possibly different)
    encoders, and then does a cosine similarity and normalizes to get a
    distribution over the set of sentences.

    Note that in some cases, this may not be exactly "Siamese" because the
    question and sentences encoders can differ.

    Parameters
    ----------
    num_hidden_seq2seq_layers : int, optional (default: ``2``)
        We use a few stacked biLSTMs (or similar), to give the model some
        depth.  This parameter controls how many deep layers we should use.

    share_hidden_seq2seq_layers : bool, optional (default: ``False``)
        Whether or not to encode the sentences and the question with the same
        hidden seq2seq layers, or have different ones for each.

    loss_function : str, optional (default: ``"cross_entropy"``)
        Valid options here are ``"cross_entropy"``, ``"ranking"``, and ``"hinge_ranking"``.

        - ``"cross_entropy"``: with this loss function, we will train the model using cross entropy
          loss after a final softmax.  This means that there is exactly one correct answer, and you
          want the model to assign all probability mass to that answer, and none to the incorrect
          answers.
        - ``"ranking"``: with this loss function, we will train the model to rank all positively
          labeled sentences above all negatively labeled sentences.  This allows you to have more
          than one correct answer, but has some other issues to think carefully about.  See
          :func:`ranking_loss` for more detail.
        - ``"hinge_ranking"``: with this loss function, we will train the model to score all
          positively labeled sentences at least some margin above all negatively labeled sentences.
          Similarly to ``"ranking"``, this allows you to have more than one correct answer, but has
          some other issues to think carefully about.  See :func:`ranking_loss_with_margin` for
          more detail.
    """
    loss_choices = OrderedDict()
    loss_choices["cross_entropy"] = "categorical_crossentropy"
    loss_choices["ranking"] = ranking_loss
    loss_choices["hinge_ranking"] = ranking_loss_with_margin

    def __init__(self, params: Params):
        self.num_hidden_seq2seq_layers = params.pop('num_hidden_seq2seq_layers', 2)
        self.share_hidden_seq2seq_layers = params.pop('share_hidden_seq2seq_layers', False)
        self.num_question_words = params.pop('num_question_words', None)
        self.num_sentences = params.pop('num_sentences', None)
        self.loss_function = params.pop_choice("loss_function", list(self.loss_choices.keys()),
                                               default_to_first_choice=True)
        super(SiameseSentenceSelector, self).__init__(params)
        self.loss = self.loss_choices[self.loss_function]

    @overrides
    def _build_model(self):
        """
        The basic outline here is that we'll pass the questions and each
        sentence in the passage through some sort of encoder (e.g. BOW, GRU,
        or biGRU).

        Then, we take the encoded representation of the question and calculate
        a cosine similarity with the encoded representation of each sentence in
        the passage, to get a tensor of cosine similarities with shape
        (batch_size, num_sentences_per_passage). We then normalize for each
        batch to get a probability distribution over sentences in the passage.
        """
        # First we create input layers and pass the inputs through embedding layers.
        # shape: (batch size, num_question_words)
        question_input = Input(shape=self._get_sentence_shape(self.num_question_words),
                               dtype='int32', name="question_input")

        # shape: (batch size, num_sentences, num_sentence_words)
        sentences_input_shape = ((self.num_sentences,) +
                                 self._get_sentence_shape())
        sentences_input = Input(shape=sentences_input_shape,
                                dtype='int32', name="sentences_input")

        # shape: (batch size, num_question_words, embedding size)
        question_embedding = self._embed_input(question_input)

        # shape: (batch size, num_sentences, num_sentence_words, embedding size)
        sentences_embedding = self._embed_input(sentences_input)

        # We encode the question embedding with some more seq2seq layers
        modeled_question = question_embedding
        for i in range(self.num_hidden_seq2seq_layers):
            if self.share_hidden_seq2seq_layers:
                seq2seq_encoder_name = "seq2seq_{}".format(i)
            else:
                seq2seq_encoder_name = "question_seq2seq_{}".format(i)
            hidden_layer = self._get_seq2seq_encoder(name=seq2seq_encoder_name,
                                                     fallback_behavior="use default params")
            # shape: (batch_size, num_question_words, seq2seq output dimension)
            modeled_question = hidden_layer(modeled_question)

        # We encode the sentence embedding with some more seq2seq layers
        collapsed_sentences = CollapseToBatch(1)(sentences_embedding)
        modeled_sentences = collapsed_sentences
        for i in range(self.num_hidden_seq2seq_layers):
            if self.share_hidden_seq2seq_layers:
                seq2seq_encoder_name = "seq2seq_{}".format(i)
            else:
                seq2seq_encoder_name = "sentence_seq2seq_{}".format(i)

            hidden_layer = self._get_seq2seq_encoder(name=seq2seq_encoder_name,
                                                     fallback_behavior="use default params")
            # shape: (batch_size * num_sentences, num_question_words, seq2seq output dimension)
            modeled_sentences = hidden_layer(modeled_sentences)

        # We encode the modeled question with some encoder.
        question_encoder = self._get_encoder(name="question", fallback_behavior="use default encoder")
        # shape: (batch size, encoder_output_dimension)
        encoded_question = question_encoder(modeled_question)

        # We encode the modeled document with some encoder.
        sentences_encoder = self._get_encoder(name="sentence", fallback_behavior="use default encoder")
        # shape: (batch size * num_sentences, encoder_output_dimension)
        encoded_sentences = sentences_encoder(modeled_sentences)
        encoded_sentences = AddEncoderMask()([encoded_sentences, modeled_sentences])
        encoded_sentences = ExpandFromBatch(1)([encoded_sentences, sentences_embedding])

        # Here we use the Attention layer with the cosine similarity function
        # to get the cosine similarities of each sesntence with the question.
        # shape: (batch size, num_sentences)
        attention_name = 'question_sentences_similarity'
        similarity_params = Params({"type": "cosine_similarity"})
        attention_layer = Attention(name=attention_name,
                                    similarity_function=similarity_params.as_dict(),
                                    normalize=self.loss_function == "cross_entropy")
        sentence_probabilities = attention_layer([encoded_question, encoded_sentences])
        if self.loss_function != "cross_entropy":
            replace_layer = ReplaceMaskedValues(replace_with=VERY_NEGATIVE_NUMBER)
            sentence_probabilities = replace_layer(sentence_probabilities)

        return DeepQaModel(input=[question_input, sentences_input],
                           output=sentence_probabilities)

    @overrides
    def _instance_type(self):
        """
        Return the instance type that the model trains on.
        """
        return SentenceSelectionInstance

    @overrides
    def get_padding_lengths(self) -> Dict[str, int]:
        """
        Return a dictionary with the appropriate padding lengths.
        """
        padding_lengths = super(SiameseSentenceSelector, self).get_padding_lengths()
        padding_lengths['num_question_words'] = self.num_question_words
        padding_lengths['num_sentences'] = self.num_sentences
        return padding_lengths

    @overrides
    def get_instance_sorting_keys(self) -> List[str]:  # pylint: disable=no-self-use
        return ['num_sentence_words', 'num_question_words']

    @overrides
    def _set_padding_lengths(self, padding_lengths: Dict[str, int]):
        """
        Set the padding lengths of the model.
        """
        super(SiameseSentenceSelector, self)._set_padding_lengths(padding_lengths)
        if self.num_question_words is None:
            self.num_question_words = padding_lengths['num_question_words']
        if self.num_sentences is None:
            self.num_sentences = padding_lengths['num_sentences']

    @overrides
    def _set_padding_lengths_from_model(self):
        self._set_text_lengths_from_model_input(self.model.get_input_shape_at(0)[1][2:])
        self.num_question_words = self.model.get_input_shape_at(0)[0][1]
        self.num_sentences = self.model.get_input_shape_at(0)[1][1]

    @classmethod
    def _get_custom_objects(cls):
        custom_objects = super(SiameseSentenceSelector, cls)._get_custom_objects()
        custom_objects["AddEncoderMask"] = AddEncoderMask
        custom_objects["Attention"] = Attention
        custom_objects["CollapseToBatch"] = CollapseToBatch
        custom_objects["ExpandFromBatch"] = ExpandFromBatch
        custom_objects["ReplaceMaskedValues"] = ReplaceMaskedValues
        return custom_objects
